package com.example.android.displayingbitmaps.util;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.Bitmap.Config;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Build.VERSION_CODES;
import android.os.Environment;
import android.os.StatFs;
import android.support.v4.util.LruCache;
import android.util.Log;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.SoftReference;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * 图片缓存
 * 
 * @author lgh
 * 
 */
@TargetApi(VERSION_CODES.HONEYCOMB_MR1)
public class ImageCache {
	private static final String TAG = "ImageCache";

	// 软引用最大长度
	private final int SOFTREFERENCE_CACHE_SIZE = 10;
	// 开辟8M硬缓存空间
	private final static int DEFAULT_MEM_CACHE_SIZE = 8 * 1024 * 1024;

	// Default disk cache size in bytes
	private static final int DEFAULT_DISK_CACHE_SIZE = 1024 * 1024 * 50; // 50MB

	// Compression settings when writing images to disk cache
	private static final CompressFormat DEFAULT_COMPRESS_FORMAT = CompressFormat.JPEG;
	private static final int DEFAULT_COMPRESS_QUALITY = 70;
	private static final int DISK_CACHE_INDEX = 0;

	// Constants to easily toggle various caches
	private static final boolean DEFAULT_MEM_CACHE_ENABLED = true;
	private static final boolean DEFAULT_DISK_CACHE_ENABLED = true;
	private static final boolean DEFAULT_INIT_DISK_CACHE_ON_CREATE = true;

	private DiskLruCache mDiskLruCache;
	private LruCache<String, Bitmap> mMemoryCache;
	private ImageCacheParams mCacheParams;
	private final Object mDiskCacheLock = new Object();
	private Set<SoftReference<Bitmap>> mReusableBitmaps;

	private static ImageCache mImageCache;
	private static final String IMAGE_CACHE_DIR = "images";

	public static ImageCache getInstance(ImageCacheParams cacheParams) {
		if (mImageCache == null) {
			mImageCache = new ImageCache(cacheParams);
		}
		return mImageCache;
	}

	private ImageCache(ImageCacheParams cacheParams) {
		init(cacheParams);
	}

	/**
	 * Initialize the cache, providing all parameters.
	 * 
	 * @param cacheParams
	 *            The cache parameters to initialize the cache
	 */
	private void init(ImageCacheParams cacheParams) {
		if (cacheParams != null) {
			mCacheParams = cacheParams;
		} else {
			mCacheParams = new ImageCacheParams(DisplayingBitmapsApplication.getmInstance().getApplicationContext(),
					IMAGE_CACHE_DIR);
			mCacheParams.setMemCacheSizePercent(0.25f);
		}

		// BEGIN_INCLUDE(init_memory_cache)
		// Set up memory cache
		if (mCacheParams.memoryCacheEnabled) {
			// If we're running on Honeycomb or newer, create a set of reusable
			// bitmaps that can be
			// populated into the inBitmap field of BitmapFactory.Options. Note
			// that the set is
			// of SoftReferences which will actually not be very effective due
			// to the garbage
			// collector being aggressive clearing Soft/WeakReferences. A better
			// approach
			// would be to use a strongly references bitmaps, however this would
			// require some
			// balancing of memory usage between this set and the bitmap
			// LruCache. It would also
			// require knowledge of the expected size of the bitmaps. From
			// Honeycomb to JellyBean
			// the size would need to be precise, from KitKat onward the size
			// would just need to
			// be the upper bound (due to changes in how inBitmap can re-use
			// bitmaps).
			if (Build.VERSION.SDK_INT >= VERSION_CODES.HONEYCOMB) {
				mReusableBitmaps = Collections
						.synchronizedSet(new HashSet<SoftReference<Bitmap>>());
			}

			mMemoryCache = new LruCache<String, Bitmap>(mCacheParams.memCacheSize) {

				/**
				 * Notify the removed entry that is no longer being cached
				 */
				@Override
				protected void entryRemoved(boolean evicted, String key, Bitmap oldValue,
						Bitmap newValue) {
					// The removed entry is a standard BitmapDrawable

					if (Build.VERSION.SDK_INT >= VERSION_CODES.HONEYCOMB) {
						// We're running on Honeycomb or later, so add the
						// bitmap
						// to a SoftReference set for possible use with inBitmap
						// later
						mReusableBitmaps.add(new SoftReference<Bitmap>(oldValue));
					}
				}

				/**
				 * Measure item size in kilobytes rather than units which is
				 * more practical for a bitmap cache
				 */
				@Override
				protected int sizeOf(String key, Bitmap value) {
					final int bitmapSize = getBitmapSize(value) / 1024;
					return bitmapSize == 0 ? 1 : bitmapSize;
				}
			};
		}
		// By default the disk cache is not initialized here as it should be
		// initialized
		// on a separate thread due to disk access.
		if (mCacheParams.initDiskCacheOnCreate) {
			// Set up disk cache
			initDiskCache();
		}
	}

	/**
	 * Initializes the disk cache. Note that this includes disk access so this
	 * should not be executed on the main/UI thread. By default an ImageCache
	 * does not initialize the disk cache when it is created, instead you should
	 * call initDiskCache() to initialize it on a background thread.
	 */
	public void initDiskCache() {
		// Set up disk cache
		synchronized (mDiskCacheLock) {
			if (mDiskLruCache == null || mDiskLruCache.isClosed()) {
				File diskCacheDir = mCacheParams.diskCacheDir;
				if (mCacheParams.diskCacheEnabled && diskCacheDir != null) {
					if (!diskCacheDir.exists()) {
						diskCacheDir.mkdirs();
					}
					if (getUsableSpace(diskCacheDir) > mCacheParams.diskCacheSize) {
						try {
							mDiskLruCache = DiskLruCache.open(diskCacheDir, 1, 1,
									mCacheParams.diskCacheSize);
						} catch (final IOException e) {
							mCacheParams.diskCacheDir = null;
							Log.e(TAG,"initDiskCache - " + e);
						}
					}
				}
			}
			mDiskCacheLock.notifyAll();
		}
	}

	public DiskLruCache getDiskLruCache() {
		return mDiskLruCache;
	}

	/**
	 * Adds a bitmap to both memory and disk cache.
	 * 
	 * @param data
	 *            Unique identifier for the bitmap to store
	 * @param value
	 *            The bitmap drawable to store
	 */
	public void addBitmapToCache(String data, Bitmap value) {
		// BEGIN_INCLUDE(add_bitmap_to_cache)
		if (data == null || value == null) {
			return;
		}

		// Add to memory cache
		if (mMemoryCache != null) {
			mMemoryCache.put(data, value);
		}

		synchronized (mDiskCacheLock) {
			// Add to disk cache
			if (mDiskLruCache != null) {
				final String key = hashKeyForDisk(data);
				OutputStream out = null;
				try {
					DiskLruCache.Snapshot snapshot = mDiskLruCache.get(key);
					if (snapshot == null) {
						final DiskLruCache.Editor editor = mDiskLruCache.edit(key);
						if (editor != null) {
							out = editor.newOutputStream(DISK_CACHE_INDEX);
							value.compress(mCacheParams.compressFormat,
									mCacheParams.compressQuality, out);
							editor.commit();
							out.close();
						}
					} else {
						snapshot.getInputStream(DISK_CACHE_INDEX).close();
					}
				} catch (final IOException e) {
					Log.e(TAG,"addBitmapToCache - " + e);
				} catch (Exception e) {
					Log.e(TAG, "addBitmapToCache - " + e);
				} finally {
					try {
						if (out != null) {
							out.close();
						}
					} catch (IOException e) {
					}
				}
			}
		}
		// END_INCLUDE(add_bitmap_to_cache)
	}

	/**
	 * Get from memory cache.
	 * 
	 * @param data
	 *            Unique identifier for which item to get
	 * @return The bitmap drawable if found in cache, null otherwise
	 */
	public Bitmap getBitmapFromMemCache(String data) {
		Bitmap memValue = null;

		if (mMemoryCache != null) {
			memValue = mMemoryCache.get(data);
		}
		return memValue;
	}

	/**
	 * Get from disk cache.
	 * 
	 * @param data
	 *            Unique identifier for which item to get
	 * @return The bitmap if found in cache, null otherwise
	 */
	public Bitmap getBitmapFromDiskCache(String data) {
		final String key = hashKeyForDisk(data);
		Bitmap bitmap = null;

		synchronized (mDiskCacheLock) {
			if (mDiskLruCache != null) {
				InputStream inputStream = null;
				try {
					final DiskLruCache.Snapshot snapshot = mDiskLruCache.get(key);
					if (snapshot != null) {
						inputStream = snapshot.getInputStream(DISK_CACHE_INDEX);
						if (inputStream != null) {
							FileDescriptor fd = ((FileInputStream) inputStream).getFD();
							bitmap = ImageResizer.decodeSampledBitmapFromDescriptor(fd,
									Integer.MAX_VALUE, Integer.MAX_VALUE, this);
						}
					}
				} catch (final IOException e) {
					Log.e(TAG,"getBitmapFromDiskCache - " + e);
				} finally {
					try {
						if (inputStream != null) {
							inputStream.close();
						}
					} catch (IOException e) {
					}
				}
			}
			return bitmap;
		}
	}

	/**
	 * @param options
	 *            - BitmapFactory.Options with out* options populated
	 * @return Bitmap that case be used for inBitmap
	 */
	public Bitmap getBitmapFromReusableSet(BitmapFactory.Options options) {
		Bitmap bitmap = null;

		if (mReusableBitmaps != null && !mReusableBitmaps.isEmpty()) {
			synchronized (mReusableBitmaps) {
				final Iterator<SoftReference<Bitmap>> iterator = mReusableBitmaps.iterator();
				Bitmap item;

				while (iterator.hasNext()) {
					item = iterator.next().get();

					if (null != item && item.isMutable()) {
						// Check to see it the item can be used for inBitmap
						if (canUseForInBitmap(item, options)) {
							bitmap = item;

							// Remove from reusable set so it can't be used
							// again
							iterator.remove();
							break;
						}
					} else {
						// Remove from the set if the reference has been
						// cleared.
						iterator.remove();
					}
				}
			}
		}

		return bitmap;
	}

	/**
	 * Clears both the memory and disk cache associated with this ImageCache
	 * object. Note that this includes disk access so this should not be
	 * executed on the main/UI thread.
	 */
	public void clearCache() {
		if (mMemoryCache != null) {
			mMemoryCache.evictAll();
		}

		synchronized (mDiskCacheLock) {
			if (mDiskLruCache != null && !mDiskLruCache.isClosed()) {
				try {
					mDiskLruCache.delete();
				} catch (IOException e) {
					Log.e(TAG,"clearCache - " + e);
				}
				mDiskLruCache = null;
				initDiskCache();
			}
		}
	}

	/**
	 * Flushes the disk cache associated with this ImageCache object. Note that
	 * this includes disk access so this should not be executed on the main/UI
	 * thread.
	 */
	public void flush() {
		synchronized (mDiskCacheLock) {
			if (mDiskLruCache != null) {
				try {
					mDiskLruCache.flush();
				} catch (IOException e) {
					Log.e(TAG,"flush - " + e);
				}
			}
		}
	}

	/**
	 * Closes the disk cache associated with this ImageCache object. Note that
	 * this includes disk access so this should not be executed on the main/UI
	 * thread.
	 */
	public void close() {
		synchronized (mDiskCacheLock) {
			if (mDiskLruCache != null) {
				try {
					if (!mDiskLruCache.isClosed()) {
						mDiskLruCache.close();
						mDiskLruCache = null;
					}
				} catch (IOException e) {
					Log.e(TAG,"close - " + e);
				}
			}
		}
	}

	/**
	 * @param candidate
	 *            - Bitmap to check
	 * @param targetOptions
	 *            - Options that have the out* value populated
	 * @return true if <code>candidate</code> can be used for inBitmap re-use
	 *         with <code>targetOptions</code>
	 */
	@TargetApi(VERSION_CODES.KITKAT)
	private static boolean canUseForInBitmap(Bitmap candidate, BitmapFactory.Options targetOptions) {
		// BEGIN_INCLUDE(can_use_for_inbitmap)
		if (!(Build.VERSION.SDK_INT >= VERSION_CODES.KITKAT)) {
			// On earlier versions, the dimensions must match exactly and the
			// inSampleSize must be 1
			return candidate.getWidth() == targetOptions.outWidth
					&& candidate.getHeight() == targetOptions.outHeight
					&& targetOptions.inSampleSize == 1;
		}

		// From Android 4.4 (KitKat) onward we can re-use if the byte size of
		// the new bitmap
		// is smaller than the reusable bitmap candidate allocation byte count.
		int width = targetOptions.outWidth / targetOptions.inSampleSize;
		int height = targetOptions.outHeight / targetOptions.inSampleSize;
		int byteCount = width * height * getBytesPerPixel(candidate.getConfig());
		return byteCount <= candidate.getAllocationByteCount();
	}

	/**
	 * Return the byte usage per pixel of a bitmap based on its configuration.
	 * 
	 * @param config
	 *            The bitmap configuration.
	 * @return The byte usage per pixel.
	 */
	private static int getBytesPerPixel(Config config) {
		if (config == Config.ARGB_8888) {
			return 4;
		} else if (config == Config.RGB_565) {
			return 2;
		} else if (config == Config.ARGB_4444) {
			return 2;
		} else if (config == Config.ALPHA_8) {
			return 1;
		}
		return 1;
	}

	/**
	 * A hashing method that changes a string (like a URL) into a hash suitable
	 * for using as a disk filename.
	 */
	public static String hashKeyForDisk(String key) {
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

	@TargetApi(VERSION_CODES.KITKAT)
	public static int getBitmapSize(Bitmap value) {
		// From KitKat onward use getAllocationByteCount() as allocated bytes
		// can potentially be
		// larger than bitmap byte count.
		if (Build.VERSION.SDK_INT >= VERSION_CODES.KITKAT) {
			return value.getAllocationByteCount();
		}

		if (Build.VERSION.SDK_INT >= VERSION_CODES.HONEYCOMB_MR1) {
			return value.getByteCount();
		}

		// Pre HC-MR1
		return value.getRowBytes() * value.getHeight();
	}

	public static File getDiskCacheDir(Context context, String uniqueName) {
		// Check if media is mounted or storage is built-in, if so, try and use
		// external cache dir
		// otherwise use internal cache dir
		final String cachePath = Environment.MEDIA_MOUNTED.equals(Environment
				.getExternalStorageState()) || !isExternalStorageRemovable() ? getExternalCacheDir(
				context).getPath() : context.getCacheDir().getPath();

		return new File(cachePath + File.separator + uniqueName);
	}

	public static File getExternalCacheDir(Context context) {
		if (Build.VERSION.SDK_INT >= VERSION_CODES.FROYO) {
			return context.getExternalCacheDir();
		}

		// Before Froyo we need to construct the external cache dir ourselves
		final String cacheDir = "/Android/data/" + context.getPackageName() + "/cache/";
		return new File(Environment.getExternalStorageDirectory().getPath() + cacheDir);
	}

	public static boolean isExternalStorageRemovable() {
		if (Build.VERSION.SDK_INT >= VERSION_CODES.GINGERBREAD) {
			return Environment.isExternalStorageRemovable();
		}
		return true;
	}

	public static long getUsableSpace(File path) {
		if (Build.VERSION.SDK_INT >= VERSION_CODES.GINGERBREAD) {
			return path.getUsableSpace();
		}
		final StatFs stats = new StatFs(path.getPath());
		return (long) stats.getBlockSize() * (long) stats.getAvailableBlocks();
	}

	/**
	 * A holder class that contains cache parameters.
	 */
	public static class ImageCacheParams {
		public int memCacheSize = DEFAULT_MEM_CACHE_SIZE;
		public int diskCacheSize = DEFAULT_DISK_CACHE_SIZE;
		public File diskCacheDir;
		public CompressFormat compressFormat = DEFAULT_COMPRESS_FORMAT;
		public int compressQuality = DEFAULT_COMPRESS_QUALITY;
		public boolean memoryCacheEnabled = DEFAULT_MEM_CACHE_ENABLED;
		public boolean diskCacheEnabled = DEFAULT_DISK_CACHE_ENABLED;
		public boolean initDiskCacheOnCreate = DEFAULT_INIT_DISK_CACHE_ON_CREATE;

		public ImageCacheParams(Context context, String diskCacheDirectoryName) {
			diskCacheDir = getDiskCacheDir(context, diskCacheDirectoryName);
		}

		/**
		 * @param percent
		 *            Percent of available app memory to use to size memory
		 *            cache
		 */
		public void setMemCacheSizePercent(float percent) {
			if (percent < 0.01f || percent > 0.8f) {
				throw new IllegalArgumentException("setMemCacheSizePercent - percent must be "
						+ "between 0.01 and 0.8 (inclusive)");
			}
			memCacheSize = Math.round(percent * Runtime.getRuntime().maxMemory() / 1024);
		}
	}

}
