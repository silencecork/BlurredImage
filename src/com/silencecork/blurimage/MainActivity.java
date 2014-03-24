package com.silencecork.blurimage;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.util.SparseArray;
import android.view.View;

public class MainActivity extends Activity {
	
	private static final int MODE_HANDLE_IN_INTENT = 0;
	
	private static final int MODE_HANDLE_IN_ACTIVITY_RESULT = 1;
	
	private int mMode = MODE_HANDLE_IN_INTENT;
	
	private SparseArray<OnPhotoChooseOperator> mOperationMap = new SparseArray<OnPhotoChooseOperator>();
	
	private BlurredImageView mImageView;
	
	private Bitmap mOriginalBitmap;
	

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);
		
		mImageView = (BlurredImageView) findViewById(R.id.image1);
		
		mOperationMap.put(MODE_HANDLE_IN_INTENT, new HandleInIntent(this));
		mOperationMap.put(MODE_HANDLE_IN_ACTIVITY_RESULT, new HandleInActivityResult(this));
	}

	public void choosePhoto(View v) {
		OnPhotoChooseOperator operator = mOperationMap.get(mMode);
		Intent intent = operator.onCreateIntent();
		startActivityForResult(intent, 0);
	}
	
	public void playAnim(View v) {
		mImageView.playAnimation(-1);
	}
	
	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		if (resultCode == RESULT_OK) {
			Uri uri = data.getData();
			OnPhotoChooseOperator operator = mOperationMap.get(mMode);
			mOriginalBitmap = operator.onHandleResult(requestCode, uri);
			if (mOriginalBitmap != null) {
				mImageView.setImageBitmapForAnimation(mOriginalBitmap);
			}
		}
	}
	
	@Override
	public Dialog onCreateDialog(int id, Bundle data) {
		String message = data.getString("message");
		AlertDialog.Builder b = new AlertDialog.Builder(this);
		b.setTitle(R.string.app_name).setMessage(message).setPositiveButton(android.R.string.ok, null);
		
		return b.create();
	}
 }
