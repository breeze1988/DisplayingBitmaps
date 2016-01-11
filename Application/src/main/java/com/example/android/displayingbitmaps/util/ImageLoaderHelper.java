package com.example.android.displayingbitmaps.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.ImageView;

import com.example.android.displayingbitmaps.R;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author wxh:
 * @version 创建时间：2015-12-22 上午10:46:14 类说明
 */
public class ImageLoaderHelper {

	private static final String TAG = "ImageLoaderHelper";

	private static final int MESSAGE_POST_RESULT = 1;
	private static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();
	private static final int CORE_POOL_SIZE = CPU_COUNT + 1;
	private static final int MAXIMUM_POOL_SIZE = CPU_COUNT * 2 + 1;
	private static final long KEEP_ALIVE = 10L;

	private static final int TAG_KEY_URI = R.id.imageView;
	private static final int IO_BUFFER_SIZE = 8 * 1024;
	private static final int DISK_CACHE_INDEX = 0;
	private boolean mIsDiskLruCacheCreated = false;

	private Context mContext;

	private ImageCache mImageCache;
	private ImageCache.ImageCacheParams mImageCacheParams;

	private static final ThreadFactory sThreadFactory = new ThreadFactory() {
		private final AtomicInteger mCount = new AtomicInteger(1);

		@Override
		public Thread newThread(Runnable r) {
			return new Thread(r, "ImageLoader#" + mCount.getAndDecrement());
		}
	};

	private static final Executor THREAD_POOL_EXECUTOR = new ThreadPoolExecutor(CORE_POOL_SIZE,
			MAXIMUM_POOL_SIZE, KEEP_ALIVE, TimeUnit.SECONDS, new LinkedBlockingDeque<Runnable>(),
			sThreadFactory);


	private static Handler mMainHandler = new Handler(Looper.getMainLooper()) {
		public void handleMessage(android.os.Message msg) {
			LoaderResult result = (LoaderResult) msg.obj;
			ImageView imageView = result.imageView;
			BitmapCallback callbcCallback = result.mCallback;
			String uri = (String) imageView.getTag(TAG_KEY_URI);
			if (uri.equals(result.uri)) {
				if (callbcCallback == null) {
					imageView.setImageBitmap(result.bitmap);
				} else {
					callbcCallback.BitmapLoaded(imageView, result.bitmap);
				}
			} else {
				Log.i(TAG,"set image bitmap,but url has changed,ignored!");
			}
		};
	};

	private ImageResizer mImageResizer = new ImageResizer();
	private DiskLruCache mDiskLruCache;

	private ImageLoaderHelper(Context context,ImageCache.ImageCacheParams ImageCacheParams) {
		mContext = context;
		mImageCacheParams = ImageCacheParams;
		if(mImageCache == null){
			mImageCache = ImageCache.getInstance(mImageCacheParams);
		}
		mDiskLruCache = mImageCache.getDiskLruCache();
	}

	public static ImageLoaderHelper build(Context context,ImageCache.ImageCacheParams ImageCacheParams) {
		return new ImageLoaderHelper(context,ImageCacheParams);
	}

	/**
	 * 加載bitmap 從 內存緩存，磁盤緩存，網絡，并綁定bitmap和imageview
	 * 
	 * @param uri
	 * @param imageview
	 */
	public void bindBitmap(final String uri, final ImageView imageview) {
		bindBitmapPerfer(uri, imageview, 0, 0, null);
	}

	public void bindBitmap(final String uri, final ImageView imageview, BitmapCallback callback) {
		bindBitmapPerfer(uri, imageview, 0, 0, callback);
	}

	private void bindBitmapPerfer(final String uri, final ImageView imageview, final int reqWidth,
			final int reqHeight, final BitmapCallback bitmapCallback) {
		imageview.setTag(TAG_KEY_URI, uri);
		Bitmap bitmap = null;
		if(mImageCache != null){
			bitmap = mImageCache.getBitmapFromMemCache(uri);
		}
		if (bitmap != null) {
			if (bitmapCallback == null) {
				imageview.setImageBitmap(bitmap);
			} else {
				bitmapCallback.BitmapLoaded(imageview, bitmap);
			}
			return;
		}
		BitmapWorkerTask loadBitmapTask = new BitmapWorkerTask(uri, imageview, bitmapCallback);
		THREAD_POOL_EXECUTOR.execute(loadBitmapTask);
	}


	private class BitmapWorkerTask implements Runnable{
		private String mUri;
		private final WeakReference<ImageView> imageViewReference;
		private final ImageView mImageView;
		private final BitmapCallback mBitmapCallback;

		public BitmapWorkerTask(String uri,ImageView imageview,BitmapCallback bitmapCallback){
			mUri = uri;
			mImageView = imageview;
			mBitmapCallback = bitmapCallback;
			imageViewReference = new WeakReference<ImageView>(imageview);
		}
		@Override
		public void run() {
			Bitmap bitmap = loadBitmap(mUri, 0, 0);
			if (bitmap != null) {
				LoaderResult result = new LoaderResult(mImageView, mUri, bitmap, mBitmapCallback);
				mMainHandler.obtainMessage(MESSAGE_POST_RESULT, result).sendToTarget();
			}
		}
	};

	public Bitmap loadBitmap(String uri, int reqWidth, int reqHeight) {
		Bitmap bitmap = mImageCache.getBitmapFromMemCache(uri);
		if (bitmap != null) {
			Log.i(TAG,"bitmap从內存中获得,uri:" + uri);
			return bitmap;
		}
		try {
			bitmap = mImageCache.getBitmapFromDiskCache(uri);
			if (bitmap != null) {
				Log.i(TAG,"bitmap从磁盘缓存中获得,uri = " + uri);
				return bitmap;
			}
			bitmap = loadBitmapFromHttp(uri, reqWidth, reqHeight);
			if (bitmap != null) {
				Log.i(TAG,"bitmap从http获得,url:" + uri);
				return bitmap;
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		if (bitmap == null && !mIsDiskLruCacheCreated) {
			Log.i(TAG,"DiskLruCach 不可用，bitmap从网络下载");
			bitmap = downloadBitmapFromUrl(uri);
		}
		return bitmap;
	}

	private Bitmap downloadBitmapFromUrl(String urlString) {
		if (!isNetworkAvailable()) {
			return null;
		}
		Bitmap bitmap = null;
		HttpURLConnection conn = null;
		BufferedInputStream in = null;
		try {
			final URL url = new URL(urlString);
			conn = (HttpURLConnection) url.openConnection();
			in = new BufferedInputStream(conn.getInputStream(), IO_BUFFER_SIZE);
			bitmap = BitmapFactory.decodeStream(in);
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			if (conn != null) {
				conn.disconnect();
			}
			StreamUtils.closeIO(in);
		}
		return bitmap;
	}

	private Bitmap loadBitmapFromHttp(String url, int reqWidth, int reqHeight) throws IOException {
		if (Looper.myLooper() == Looper.getMainLooper()) {
			Log.e(TAG,"http獲取圖片在主線程操作");
		}
		if (mDiskLruCache == null) {
			return null;
		}
		String key = hashKeyForUrl(url);
		synchronized (ImageLoaderHelper.class) {
			DiskLruCache.Editor editor = mDiskLruCache.edit(key);
			if (editor != null) {
				OutputStream outputStream = editor.newOutputStream(DISK_CACHE_INDEX);
				if (downloadUrlToStream(url, outputStream)) {
					editor.commit();
				} else {
					editor.abort();
				}
				mDiskLruCache.flush();
			}
		}
		return loadBitmapFromDiskCache(url, reqWidth, reqHeight);
	}

	public boolean downloadUrlToStream(String urlString, OutputStream outputStream) {
		if (!isNetworkAvailable()) {
			return false;
		}

		HttpURLConnection conn = null;
		BufferedOutputStream out = null;
		BufferedInputStream in = null;
		try {
			final URL url = new URL(urlString);
			conn = (HttpURLConnection) url.openConnection();
			in = new BufferedInputStream(conn.getInputStream(), IO_BUFFER_SIZE);
			out = new BufferedOutputStream(outputStream, IO_BUFFER_SIZE);
			byte[] buffer = new byte[1024 * 2];
			int len;
			while ((len = in.read(buffer)) != -1) {
				out.write(buffer, 0, len);
			}
			return true;
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			if (conn != null) {
				conn.disconnect();
			}
			StreamUtils.closeIO(in);
			StreamUtils.closeIO(out);
		}
		return false;
	}

	private Bitmap loadBitmapFromDiskCache(String url, int reqWidth, int reqHeight)
			throws IOException {
		if (Looper.myLooper() == Looper.getMainLooper()) {
			Log.e(TAG,"磁盤內存獲取圖片在主線程操作，不推薦");
		}
		if (mImageCache == null) {
			return null;
		}
		Bitmap bitmap = null;
		FileInputStream in = null;
		FileDescriptor fd = null;
		String key = hashKeyForUrl(url);
		DiskLruCache.Snapshot snapShot;
		try {
			snapShot = mDiskLruCache.get(key);
			if (snapShot != null) {
				in = (FileInputStream) snapShot.getInputStream(DISK_CACHE_INDEX);
				fd = in.getFD();
				bitmap = mImageResizer.decodeSampledBitmapFromFileDescriptor(fd, reqWidth,
						reqHeight);
				if (bitmap != null) {
					mImageCache.addBitmapToCache(key, bitmap);
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			StreamUtils.closeIO(in);
		}
		return bitmap;
	}

	/**
	 * A hashing method that changes a string (like a URL) into a hash suitable
	 * for using as a disk filename.
	 */
	public static String hashKeyForUrl(String key) {
		String cacheKey;
		try {
			final MessageDigest mDigest = MessageDigest.getInstance("MD5");
			mDigest.update(key.getBytes());
			cacheKey = bytesToHexString(mDigest.digest());
		} catch (NoSuchAlgorithmException e) {
			cacheKey = String.valueOf(key.hashCode());
		}
		return cacheKey;
	}

	private static String bytesToHexString(byte[] bytes) {
		// http://stackoverflow.com/questions/332079
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < bytes.length; i++) {
			String hex = Integer.toHexString(0xFF & bytes[i]);
			if (hex.length() == 1) {
				sb.append('0');
			}
			sb.append(hex);
		}
		return sb.toString();
	}

	public void clearCacheInternal() {
		if (mImageCache != null) {
			mImageCache.clearCache();
		}
	}

	public void flushCacheInternal() {
		if (mImageCache != null) {
			mImageCache.flush();
		}
	}

	public void closeCacheInternal() {
		if (mImageCache != null) {
			mImageCache.close();
			mImageCache = null;
		}
	}

	private boolean isNetworkAvailable() {
		ConnectivityManager cm = (ConnectivityManager) mContext.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
		if (cm != null) {
			NetworkInfo info = cm.getActiveNetworkInfo();
			return info != null && info.isConnectedOrConnecting();
		}
		return false;
	}

	private static class LoaderResult {
		public ImageView imageView;
		public String uri;
		public Bitmap bitmap;
		public BitmapCallback mCallback;

		@SuppressWarnings("unused")
		public LoaderResult(ImageView imageView, String uri, Bitmap bitmap) {
			this.imageView = imageView;
			this.uri = uri;
			this.bitmap = bitmap;
		}

		public LoaderResult(ImageView imageView, String uri, Bitmap bitmap, BitmapCallback callback) {
			this.imageView = imageView;
			this.uri = uri;
			this.bitmap = bitmap;
			this.mCallback = callback;
		}
	}

	public interface BitmapCallback {
		public void BitmapLoaded(ImageView imageView, Bitmap bitmap);
	}
}
