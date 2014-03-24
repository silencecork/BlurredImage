package com.silencecork.blurimage;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;

public class FileUtil {
	public static String getImagePath(Context context, Uri contentUri){
		Cursor cursor = null;
		String imagePath = null;
		try {
			Log.d("", "contentUri : " + contentUri.toString());
			String scheme = contentUri.getScheme();
			if(("file").equalsIgnoreCase(scheme)){
				String path = contentUri.getPath();
				Log.d("", "path : " + path);
				return path;
			}
			if ("content".equals(scheme)) {
				String authority = contentUri.getAuthority();
				if (!MediaStore.AUTHORITY.equals(authority)) {
					Log.e("", "authority can not recongnize");
					return null;
				}
			}
			String[] proj = { MediaStore.Images.Media.DATA };
			cursor = context.getContentResolver().query(contentUri, proj, null, null, null);
			int column_index = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA);
			cursor.moveToFirst();
			imagePath = cursor.getString(column_index);
		} finally {
			if (cursor != null) {
				cursor.close();
			}
		}
		return imagePath;
	}
}
