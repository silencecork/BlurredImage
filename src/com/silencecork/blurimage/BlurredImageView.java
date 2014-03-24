package com.silencecork.blurimage;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Message;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.Interpolator;
import android.widget.ImageView;

public class BlurredImageView extends ImageView {
	
	private static final int MSG_FADE_IN = 100;
	private static final int MSG_FADE_OUT = 200;
	private static final int DEFAULT_WAIT_TIME_BETWEEN_ANIMATION = 2000;
	private int mIndex = BlurImageUtil.KEY_FRAME_COUNT;
	private int mWaitTime;
	private int mColor;
	private int mProgressAlpha;
	private int mInitAlphaMask = 125;
	
	private Bitmap mBitmap;
	private Bitmap[] mDisplayedBitmaps = new Bitmap[BlurImageUtil.KEY_FRAME_COUNT];
	private BlurImageUtil mBlurImageUtil;
	private AsyncTask<Bitmap, Void, Void> mBlurImageLoader;
	private boolean mIsPrepared;
	private Paint mPaint;
	private boolean mIsPlayDone = true;
	
	private Handler mHandler = new Handler() {

		@Override
		public void handleMessage(Message msg) {
			if (msg.what == MSG_FADE_IN) {
				fadeInAnimation();
			} else if (msg.what == MSG_FADE_OUT) {
				fadeOutAnimation();
			}
		}
		
	};
	
	public BlurredImageView(Context context) {
		super(context);
		init(context);
	}
	
	public BlurredImageView(Context context, AttributeSet attrs) {
		super(context, attrs);
		init(context);
	}
	
	public BlurredImageView(Context context, AttributeSet attrs, int defStyle) {
		super(context, attrs, defStyle);
		init(context);
	}
	
	private void init(Context context){
		mBlurImageUtil = new BlurImageUtil(context);
		mBlurImageUtil.recomputeMaxPreScaleBlurPixels();
		mColor = Color.argb(mInitAlphaMask, 0, 0, 0);
		mPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
		mPaint.setColor(mColor);
		mProgressAlpha = mInitAlphaMask / BlurImageUtil.KEY_FRAME_COUNT;
	}
	
	public void setImageBitmapForAnimation(Bitmap bm) {
		super.setImageBitmap(null);
		loadBlurKeyFrameImage(bm);
	}
	
	public boolean playAnimation(int timeToWait) {
		if (!mIsPrepared || !mIsPlayDone) {
			return false;
		}
		
		mIsPlayDone = false;
		
		if (timeToWait <= 0) {
			timeToWait = DEFAULT_WAIT_TIME_BETWEEN_ANIMATION;
		}
		
		mWaitTime = timeToWait;
		
		mHandler.sendEmptyMessage(MSG_FADE_IN);
		return true;
	}
	
	private void loadBlurKeyFrameImage(Bitmap b) {
		if (b == null || b == mBitmap) {
			return;
		}
		
		release();
		
		mIsPrepared = false;
		mBitmap = b;
		
		mBlurImageLoader = new AsyncTask<Bitmap, Void, Void>(){

			@Override
			protected Void doInBackground(Bitmap... params) {
				Bitmap b = params[0];
				for (int i = 1; i <= mDisplayedBitmaps.length; i++) {
					mDisplayedBitmaps[i - 1] = mBlurImageUtil.fastblur(b, (int) mBlurImageUtil.blurRadiusAtFrame(i));
				}
				return null;
			}

			@Override
			protected void onPostExecute(Void result) {
				mIsPrepared = true;
				setImageDrawable(new DisplayDrawable(getContext().getResources(), mDisplayedBitmaps[mDisplayedBitmaps.length - 1]));
			}
			
		};
		mBlurImageLoader.execute(b);
	}
	
	private void release() {
		if (mBitmap != null) {
			mBitmap.recycle();
		}
		
		if (mDisplayedBitmaps != null) {
			for (Bitmap bitmap : mDisplayedBitmaps) {
				if (bitmap != null) {
					bitmap.recycle();
				}
			}
		}
	}
	
	@Override
	protected void onDraw(Canvas canvas) {
		super.onDraw(canvas);
	}

	private void fadeInAnimation() {
		mIndex--;
		if (mIndex >= 0) {
			mColor = Color.argb(mInitAlphaMask - mProgressAlpha * (BlurImageUtil.KEY_FRAME_COUNT - mIndex), 0, 0, 0);
			mPaint.setColor(mColor);
			if (mDisplayedBitmaps[mIndex] != null) {
				setImageDrawable(new DisplayDrawable(getContext().getResources(), mDisplayedBitmaps[mIndex]));
			}
			invalidate();
			mHandler.sendEmptyMessageDelayed(100, 66);
		} else {
			mColor = Color.argb(mInitAlphaMask - mInitAlphaMask, 0, 0, 0);
			mPaint.setColor(mColor);
			setImageDrawable(new DisplayDrawable(getContext().getResources(), mBitmap));
			invalidate();
			mHandler.sendEmptyMessageDelayed(200, mWaitTime);
			mIndex = 0;
		}
	}
	
	private void fadeOutAnimation() {
		if (mIndex < mDisplayedBitmaps.length) {
			mColor = Color.argb(mProgressAlpha * mIndex, 0, 0, 0);
			mPaint.setColor(mColor);
			if (mDisplayedBitmaps[mIndex] != null) {
				setImageDrawable(new DisplayDrawable(getContext().getResources(), mDisplayedBitmaps[mIndex]));
			}
			invalidate();
			mHandler.sendEmptyMessageDelayed(200, 66);
			mIndex++;
		} else {
			mColor = Color.argb(mInitAlphaMask, 0, 0, 0);
			mIsPlayDone = true;
			invalidate();
		}
	}
	
	class DisplayDrawable extends BitmapDrawable {
		
		DisplayDrawable(Resources res, Bitmap b) {
			super(res, b);
		}
		
		@Override
		public void draw(Canvas canvas) {
			super.draw(canvas);
			Rect rect = getBounds();
			canvas.drawRect(rect, mPaint);
		}
		
	}

	public class BlurImageUtil {
		
		private Context mContext;
		public static final int KEY_FRAME_COUNT = 5;
		private Interpolator mBlurInterpolator = new AccelerateDecelerateInterpolator();
		private int mBlurredSampleSize  = 4;
		public static final int MAX_SUPPORTED_BLUR_PIXELS = 40;
		private int mMaxPrescaledBlurPixels;
		
		public Bitmap fastblur(Bitmap sentBitmap, int radius) {

	        // Stack Blur v1.0 from
	        // http://www.quasimondo.com/StackBlurForCanvas/StackBlurDemo.html
	        //
	        // Java Author: Mario Klingemann <mario at quasimondo.com>
	        // http://incubator.quasimondo.com
	        // created Feburary 29, 2004
	        // Android port : Yahel Bouaziz <yahel at kayenko.com>
	        // http://www.kayenko.com
	        // ported april 5th, 2012

	        // This is a compromise between Gaussian Blur and Box blur
	        // It creates much better looking blurs than Box Blur, but is
	        // 7x faster than my Gaussian Blur implementation.
	        //
	        // I called it Stack Blur because this describes best how this
	        // filter works internally: it creates a kind of moving stack
	        // of colors whilst scanning through the image. Thereby it
	        // just has to add one new block of color to the right side
	        // of the stack and remove the leftmost color. The remaining
	        // colors on the topmost layer of the stack are either added on
	        // or reduced by one, depending on if they are on the right or
	        // on the left side of the stack.
	        //
	        // If you are using this algorithm in your code please add
	        // the following line:
	        //
	        // Stack Blur Algorithm by Mario Klingemann <mario@quasimondo.com>

	        Bitmap bitmap = sentBitmap.copy(sentBitmap.getConfig(), true);

	        if (radius < 1) {
	            return (null);
	        }

	        int w = bitmap.getWidth();
	        int h = bitmap.getHeight();

	        int[] pix = new int[w * h];
	        Log.e("pix", w + " " + h + " " + pix.length);
	        bitmap.getPixels(pix, 0, w, 0, 0, w, h);

	        int wm = w - 1;
	        int hm = h - 1;
	        int wh = w * h;
	        int div = radius + radius + 1;

	        int r[] = new int[wh];
	        int g[] = new int[wh];
	        int b[] = new int[wh];
	        int rsum, gsum, bsum, x, y, i, p, yp, yi, yw;
	        int vmin[] = new int[Math.max(w, h)];

	        int divsum = (div + 1) >> 1;
	        divsum *= divsum;
	        int dv[] = new int[256 * divsum];
	        for (i = 0; i < 256 * divsum; i++) {
	            dv[i] = (i / divsum);
	        }

	        yw = yi = 0;

	        int[][] stack = new int[div][3];
	        int stackpointer;
	        int stackstart;
	        int[] sir;
	        int rbs;
	        int r1 = radius + 1;
	        int routsum, goutsum, boutsum;
	        int rinsum, ginsum, binsum;

	        for (y = 0; y < h; y++) {
	            rinsum = ginsum = binsum = routsum = goutsum = boutsum = rsum = gsum = bsum = 0;
	            for (i = -radius; i <= radius; i++) {
	                p = pix[yi + Math.min(wm, Math.max(i, 0))];
	                sir = stack[i + radius];
	                sir[0] = (p & 0xff0000) >> 16;
	                sir[1] = (p & 0x00ff00) >> 8;
	                sir[2] = (p & 0x0000ff);
	                rbs = r1 - Math.abs(i);
	                rsum += sir[0] * rbs;
	                gsum += sir[1] * rbs;
	                bsum += sir[2] * rbs;
	                if (i > 0) {
	                    rinsum += sir[0];
	                    ginsum += sir[1];
	                    binsum += sir[2];
	                } else {
	                    routsum += sir[0];
	                    goutsum += sir[1];
	                    boutsum += sir[2];
	                }
	            }
	            stackpointer = radius;

	            for (x = 0; x < w; x++) {

	                r[yi] = dv[rsum];
	                g[yi] = dv[gsum];
	                b[yi] = dv[bsum];

	                rsum -= routsum;
	                gsum -= goutsum;
	                bsum -= boutsum;

	                stackstart = stackpointer - radius + div;
	                sir = stack[stackstart % div];

	                routsum -= sir[0];
	                goutsum -= sir[1];
	                boutsum -= sir[2];

	                if (y == 0) {
	                    vmin[x] = Math.min(x + radius + 1, wm);
	                }
	                p = pix[yw + vmin[x]];

	                sir[0] = (p & 0xff0000) >> 16;
	                sir[1] = (p & 0x00ff00) >> 8;
	                sir[2] = (p & 0x0000ff);

	                rinsum += sir[0];
	                ginsum += sir[1];
	                binsum += sir[2];

	                rsum += rinsum;
	                gsum += ginsum;
	                bsum += binsum;

	                stackpointer = (stackpointer + 1) % div;
	                sir = stack[(stackpointer) % div];

	                routsum += sir[0];
	                goutsum += sir[1];
	                boutsum += sir[2];

	                rinsum -= sir[0];
	                ginsum -= sir[1];
	                binsum -= sir[2];

	                yi++;
	            }
	            yw += w;
	        }
	        for (x = 0; x < w; x++) {
	            rinsum = ginsum = binsum = routsum = goutsum = boutsum = rsum = gsum = bsum = 0;
	            yp = -radius * w;
	            for (i = -radius; i <= radius; i++) {
	                yi = Math.max(0, yp) + x;

	                sir = stack[i + radius];

	                sir[0] = r[yi];
	                sir[1] = g[yi];
	                sir[2] = b[yi];

	                rbs = r1 - Math.abs(i);

	                rsum += r[yi] * rbs;
	                gsum += g[yi] * rbs;
	                bsum += b[yi] * rbs;

	                if (i > 0) {
	                    rinsum += sir[0];
	                    ginsum += sir[1];
	                    binsum += sir[2];
	                } else {
	                    routsum += sir[0];
	                    goutsum += sir[1];
	                    boutsum += sir[2];
	                }

	                if (i < hm) {
	                    yp += w;
	                }
	            }
	            yi = x;
	            stackpointer = radius;
	            for (y = 0; y < h; y++) {
	                // Preserve alpha channel: ( 0xff000000 & pix[yi] )
	                pix[yi] = ( 0xff000000 & pix[yi] ) | ( dv[rsum] << 16 ) | ( dv[gsum] << 8 ) | dv[bsum];

	                rsum -= routsum;
	                gsum -= goutsum;
	                bsum -= boutsum;

	                stackstart = stackpointer - radius + div;
	                sir = stack[stackstart % div];

	                routsum -= sir[0];
	                goutsum -= sir[1];
	                boutsum -= sir[2];

	                if (x == 0) {
	                    vmin[y] = Math.min(y + r1, hm) * w;
	                }
	                p = x + vmin[y];

	                sir[0] = r[p];
	                sir[1] = g[p];
	                sir[2] = b[p];

	                rinsum += sir[0];
	                ginsum += sir[1];
	                binsum += sir[2];

	                rsum += rinsum;
	                gsum += ginsum;
	                bsum += binsum;

	                stackpointer = (stackpointer + 1) % div;
	                sir = stack[stackpointer];

	                routsum += sir[0];
	                goutsum += sir[1];
	                boutsum += sir[2];

	                rinsum -= sir[0];
	                ginsum -= sir[1];
	                binsum -= sir[2];

	                yi += w;
	            }
	        }

	        Log.e("pix", w + " " + h + " " + pix.length);
	        bitmap.setPixels(pix, 0, w, 0, 0, w, h);

	        return (bitmap);
	    }
		
		public void recomputeMaxPreScaleBlurPixels() {
			float maxBlurRaiusOverScreenHeight = 400 * 0.0001f;
			DisplayMetrics dm = mContext.getResources().getDisplayMetrics();
			int maxBlurPx = (int) (dm.heightPixels * maxBlurRaiusOverScreenHeight);
			while (maxBlurPx / mBlurredSampleSize > MAX_SUPPORTED_BLUR_PIXELS) {
				mBlurredSampleSize <<= 1;
			}
			mMaxPrescaledBlurPixels = maxBlurPx / mBlurredSampleSize;
		}
		
		public BlurImageUtil(Context context) {
			mContext = context;
		}
		
		public float blurRadiusAtFrame(float f) {
			return mMaxPrescaledBlurPixels * mBlurInterpolator.getInterpolation(f / KEY_FRAME_COUNT);
		}
		
	}
}
