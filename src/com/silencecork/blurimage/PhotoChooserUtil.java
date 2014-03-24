package com.silencecork.blurimage;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.MediaStore;

public class PhotoChooserUtil {
	public static Intent choosePhotoBeforeKitKat() {
		Intent intent = new Intent(Intent.ACTION_GET_CONTENT, null);
		intent.setType("image/*");
		
		return intent;
	}
	
	public static Intent choosePhotoInKitKat() {
		Intent intent = new Intent(Intent.ACTION_PICK, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
		intent.setType("image/*");
		
		return intent;
	}
	
	public static String getRealPathInKitKat(Context c, Uri uri) { // API 19 or higher, need android.permission.READ_EXTERNAL_STORAGE
		// Will return "image:x*"
		String wholeID = DocumentsContract.getDocumentId(uri);

		String id = wholeID.split(":")[1];

		String[] column = { MediaStore.Images.Media.DATA };     

		String sel = MediaStore.Images.Media._ID + "=?";

		Cursor cursor = c.getContentResolver().
		                          query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, 
		                          column, sel, new String[]{ id }, null);
		try {
			String filePath = "";

			int columnIndex = cursor.getColumnIndex(column[0]);

			if (cursor.moveToFirst()) {
				filePath = cursor.getString(columnIndex);
				
				return filePath;
			}
		} finally {
			if (cursor != null) {
				cursor.close();
			}
		}
		
		return null;
	}
	
	public static String getRealPathBeforeKitKat(Context c, Uri uri) {
		Cursor cursor = null;
		try {
			String[] proj = { MediaStore.Images.Media.DATA };
			cursor = c.getContentResolver().query(uri, proj, null, null, null);
			int column_index = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA);
			if (cursor.moveToFirst()) {
				String path = cursor.getString(column_index);
				return path;
			}
		} finally {
			if (cursor != null) {
				cursor.close();
			}
		}
		
		return null;
	}
}
