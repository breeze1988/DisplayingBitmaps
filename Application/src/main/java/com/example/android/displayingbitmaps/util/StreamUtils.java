package com.example.android.displayingbitmaps.util;

import android.database.Cursor;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

/**
 * IO工具类
 */
public class StreamUtils {
	/**
	 * 重文件中获取数据
	 * 
	 * @param file
	 * @return
	 * @throws FileNotFoundException
	 */
	public static byte[] readFile(File file) throws FileNotFoundException {
		return readInputStream(new FileInputStream(file));
	}

	/**
	 * 从输入流获取数据
	 * 
	 * @param in
	 * @return
	 */
	public static byte[] readInputStream(InputStream in) {
		byte[] buffer = new byte[1024 * 4];
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		int len = -1;
		try {
			while ((len = in.read(buffer)) != -1) {
				out.write(buffer, 0, len);
			}
			out.flush();
			return out.toByteArray();
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			closeIO(out);
			closeIO(in);
		}
		return null;
	}

	/**
	 * 关闭IO流
	 * 
	 * @param io
	 */
	public static void closeIO(Closeable io) {
		if (io != null) {
			try {
				io.close();
			} catch (IOException e) {
			}
			io = null;
		}
	}
	
	/**
	 * 关闭游标
	 * @param cursor
	 */
	public static void closeCursor(Cursor cursor){
		if(cursor!=null){
			cursor.close();
		}
		cursor=null;
	}
}
