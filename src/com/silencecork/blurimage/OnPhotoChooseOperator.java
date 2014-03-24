package com.silencecork.blurimage;

import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;

public interface OnPhotoChooseOperator {
	public Intent onCreateIntent();

	public Bitmap onHandleResult(int requestCode, Uri uri);
}