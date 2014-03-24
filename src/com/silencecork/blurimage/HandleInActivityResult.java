package com.silencecork.blurimage;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;

public class HandleInActivityResult implements OnPhotoChooseOperator {

	private Activity mContext;

	public HandleInActivityResult(Activity context) {
		mContext = context;
	}

	@Override
	public Intent onCreateIntent() {
		Intent intent = PhotoChooserUtil
				.choosePhotoBeforeKitKat();/*(Build.VERSION.SDK_INT < 19) ? PhotoChooserUtil
				.choosePhotoBeforeKitKat() : PhotoChooserUtil
				.choosePhotoInKitKat()*/;
		return intent;
	}

	@SuppressWarnings("deprecation")
	@Override
	public Bitmap onHandleResult(int requestCode, Uri uri) {
		if (requestCode == 0) {
			String path = PhotoChooserUtil.getRealPathBeforeKitKat(mContext,
					uri);
			String message = "URI: " + uri.toString() + ", PATH: " + path;
			Bundle data = new Bundle();
			data.putString("message", message);

			mContext.showDialog(0, data);
			String imagePath = FileUtil.getImagePath(mContext, uri);	
			Bitmap bitmap = ThumbnailUtils.decodeBitmapBaseOnLongSide(imagePath, 800);
			return bitmap;
		} 
		return null;
	}

}