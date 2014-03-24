package com.silencecork.blurimage;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.ExifInterface;
import android.text.TextUtils;
import android.util.Log;

/**
 * Thumbnail generation routines for media provider.
 * 
 * @author AOSP and Justin
 * 
 */
public class ThumbnailUtils {
    private static final String TAG = "ThumbnailUtils";

    /* Maximum pixels size for created bitmap. */
    private static final int MAX_NUM_PIXELS_THUMBNAIL = 512 * 384;
    private static final int MAX_NUM_PIXELS_MICRO_THUMBNAIL = 128 * 128;
    private static final int UNCONSTRAINED = -1;
    
    public static final int MINI_KIND = 1;
    public static final int FULL_SCREEN_KIND = 2;
    public static final int MICRO_KIND = 3;

    /* Options used internally. */
    private static final int OPTIONS_NONE = 0x0;
    private static final int OPTIONS_SCALE_UP = 0x1;

    /**
     * Constant used to indicate we should recycle the input in
     * {@link #extractThumbnail(Bitmap, int, int, int)} unless the output is the input.
     */
    public static final int OPTIONS_RECYCLE_INPUT = 0x2;

    /**
     * Constant used to indicate the dimension of mini thumbnail.
     * @hide Only used by media framework and media provider internally.
     */
    public static final int TARGET_SIZE_MINI_THUMBNAIL = 320;

    /**
     * Constant used to indicate the dimension of micro thumbnail.
     * @hide Only used by media framework and media provider internally.
     */
    public static final int TARGET_SIZE_MICRO_THUMBNAIL = 96;

    /**
     * This method first examines if the thumbnail embedded in EXIF is bigger than our target
     * size. If not, then it'll create a thumbnail from original image. Due to efficiency
     * consideration, we want to let MediaThumbRequest avoid calling this method twice for
     * both kinds, so it only requests for MICRO_KIND and set saveImage to true.
     *
     * This method always returns a "square thumbnail" for MICRO_KIND thumbnail.
     *
     * @param filePath the path of image file
     * @param kind could be MINI_KIND or MICRO_KIND
     * @return Bitmap, or null on failures
     *
     * @hide This method is only used by media framework and media provider internally.
     */
    public static Bitmap createImageThumbnail(String filePath, int kind) {
        boolean wantMini = (kind == MINI_KIND);
        int targetSize = wantMini
                ? TARGET_SIZE_MINI_THUMBNAIL
                : TARGET_SIZE_MICRO_THUMBNAIL;
        int maxPixels = wantMini
                ? MAX_NUM_PIXELS_THUMBNAIL
                : MAX_NUM_PIXELS_MICRO_THUMBNAIL;
        SizedThumbnailBitmap sizedThumbnailBitmap = new SizedThumbnailBitmap();
        Bitmap bitmap = null;
        int lastDot = filePath.lastIndexOf(".");
        if (lastDot < 0)
            return null;
        String extension = filePath.substring(lastDot + 1).toUpperCase();
        if (extension.equals("JPG")) {
            createThumbnailFromEXIF(filePath, targetSize, maxPixels, sizedThumbnailBitmap);
            bitmap = sizedThumbnailBitmap.mBitmap;
        }

        if (bitmap == null) {
            try {
                @SuppressWarnings("resource")
				FileDescriptor fd = new FileInputStream(filePath).getFD();
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = 1;
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeFileDescriptor(fd, null, options);
                if (options.mCancel || options.outWidth == -1
                        || options.outHeight == -1) {
                    return null;
                }
                options.inSampleSize = computeSampleSize(
                        options, targetSize, maxPixels);
                options.inJustDecodeBounds = false;

                options.inDither = false;
                options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                bitmap = BitmapFactory.decodeFileDescriptor(fd, null, options);
            } catch (IOException ex) {
                Log.e(TAG, "", ex);
            } catch (OutOfMemoryError oom) {
                Log.e(TAG, "Unable to decode file " + filePath + ". OutOfMemoryError." + oom);
            }
        }

        if (kind == MICRO_KIND) {
            // now we make it a "square thumbnail" for MICRO_KIND thumbnail
            bitmap = extractThumbnail(bitmap,
                    TARGET_SIZE_MICRO_THUMBNAIL,
                    TARGET_SIZE_MICRO_THUMBNAIL, OPTIONS_RECYCLE_INPUT);
        }
        return bitmap;
    }

    /**
     * Creates a centered bitmap of the desired size.
     *
     * @param source original bitmap source
     * @param width targeted width
     * @param height targeted height
     */
    public static Bitmap extractThumbnail(
            Bitmap source, int width, int height) {
        return extractThumbnail(source, width, height, OPTIONS_NONE);
    }

    /**
     * Creates a centered bitmap of the desired size.
     *
     * @param source original bitmap source
     * @param width targeted width
     * @param height targeted height
     * @param options options used during thumbnail extraction
     */
    public static Bitmap extractThumbnail(
            Bitmap source, int width, int height, int options) {
        if (source == null) {
            return null;
        }

        float scale;
        if (source.getWidth() < source.getHeight()) {
            scale = width / (float) source.getWidth();
        } else {
            scale = height / (float) source.getHeight();
        }
        Matrix matrix = new Matrix();
        matrix.setScale(scale, scale);
        Bitmap thumbnail = transform(matrix, source, width, height,
                OPTIONS_SCALE_UP | options);
        return thumbnail;
    }

    /*
     * Compute the sample size as a function of minSideLength
     * and maxNumOfPixels.
     * minSideLength is used to specify that minimal width or height of a
     * bitmap.
     * maxNumOfPixels is used to specify the maximal size in pixels that is
     * tolerable in terms of memory usage.
     *
     * The function returns a sample size based on the constraints.
     * Both size and minSideLength can be passed in as IImage.UNCONSTRAINED,
     * which indicates no care of the corresponding constraint.
     * The functions prefers returning a sample size that
     * generates a smaller bitmap, unless minSideLength = IImage.UNCONSTRAINED.
     *
     * Also, the function rounds up the sample size to a power of 2 or multiple
     * of 8 because BitmapFactory only honors sample size this way.
     * For example, BitmapFactory downsamples an image by 2 even though the
     * request is 3. So we round up the sample size to avoid OOM.
     */
    private static int computeSampleSize(BitmapFactory.Options options,
            int minSideLength, int maxNumOfPixels) {
        int initialSize = computeInitialSampleSize(options, minSideLength,
                maxNumOfPixels);

        int roundedSize;
        if (initialSize <= 8 ) {
            roundedSize = 1;
            while (roundedSize < initialSize) {
                roundedSize <<= 1;
            }
        } else {
            roundedSize = (initialSize + 7) / 8 * 8;
        }

        return roundedSize;
    }

    private static int computeInitialSampleSize(BitmapFactory.Options options,
            int minSideLength, int maxNumOfPixels) {
        double w = options.outWidth;
        double h = options.outHeight;

        int lowerBound = (maxNumOfPixels == UNCONSTRAINED) ? 1 :
                (int) Math.ceil(Math.sqrt(w * h / maxNumOfPixels));
        int upperBound = (minSideLength == UNCONSTRAINED) ? 128 :
                (int) Math.min(Math.floor(w / minSideLength),
                Math.floor(h / minSideLength));

        if (upperBound < lowerBound) {
            // return the larger one when there is no overlapping zone.
            return lowerBound;
        }

        if ((maxNumOfPixels == UNCONSTRAINED) &&
                (minSideLength == UNCONSTRAINED)) {
            return 1;
        } else if (minSideLength == UNCONSTRAINED) {
            return lowerBound;
        } else {
            return upperBound;
        }
    }

    /**
     * Transform source Bitmap to targeted width and height.
     */
    private static Bitmap transform(Matrix scaler,
            Bitmap source,
            int targetWidth,
            int targetHeight,
            int options) {
        boolean scaleUp = (options & OPTIONS_SCALE_UP) != 0;
        boolean recycle = (options & OPTIONS_RECYCLE_INPUT) != 0;

        int deltaX = source.getWidth() - targetWidth;
        int deltaY = source.getHeight() - targetHeight;
        if (!scaleUp && (deltaX < 0 || deltaY < 0)) {
            /*
            * In this case the bitmap is smaller, at least in one dimension,
            * than the target.  Transform it by placing as much of the image
            * as possible into the target and leaving the top/bottom or
            * left/right (or both) black.
            */
            Bitmap b2 = Bitmap.createBitmap(targetWidth, targetHeight,
            Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b2);

            int deltaXHalf = Math.max(0, deltaX / 2);
            int deltaYHalf = Math.max(0, deltaY / 2);
            Rect src = new Rect(
            deltaXHalf,
            deltaYHalf,
            deltaXHalf + Math.min(targetWidth, source.getWidth()),
            deltaYHalf + Math.min(targetHeight, source.getHeight()));
            int dstX = (targetWidth  - src.width())  / 2;
            int dstY = (targetHeight - src.height()) / 2;
            Rect dst = new Rect(
                    dstX,
                    dstY,
                    targetWidth - dstX,
                    targetHeight - dstY);
            c.drawBitmap(source, src, dst, null);
            if (recycle) {
                source.recycle();
            }
            c.setBitmap(null);
            return b2;
        }
        float bitmapWidthF = source.getWidth();
        float bitmapHeightF = source.getHeight();

        float bitmapAspect = bitmapWidthF / bitmapHeightF;
        float viewAspect   = (float) targetWidth / targetHeight;

        if (bitmapAspect > viewAspect) {
            float scale = targetHeight / bitmapHeightF;
            if (scale < .9F || scale > 1F) {
                scaler.setScale(scale, scale);
            } else {
                scaler = null;
            }
        } else {
            float scale = targetWidth / bitmapWidthF;
            if (scale < .9F || scale > 1F) {
                scaler.setScale(scale, scale);
            } else {
                scaler = null;
            }
        }

        Bitmap b1;
        if (scaler != null) {
            // this is used for minithumb and crop, so we want to filter here.
            b1 = Bitmap.createBitmap(source, 0, 0,
            source.getWidth(), source.getHeight(), scaler, true);
        } else {
            b1 = source;
        }

        if (recycle && b1 != source) {
            source.recycle();
        }

        int dx1 = Math.max(0, b1.getWidth() - targetWidth);
        int dy1 = Math.max(0, b1.getHeight() - targetHeight);

        Bitmap b2 = Bitmap.createBitmap(
                b1,
                dx1 / 2,
                dy1 / 2,
                targetWidth,
                targetHeight);

        if (b2 != b1) {
            if (recycle || b1 != source) {
                b1.recycle();
            }
        }

        return b2;
    }

    /**
     * SizedThumbnailBitmap contains the bitmap, which is downsampled either from
     * the thumbnail in exif or the full image.
     * mThumbnailData, mThumbnailWidth and mThumbnailHeight are set together only if mThumbnail
     * is not null.
     *
     * The width/height of the sized bitmap may be different from mThumbnailWidth/mThumbnailHeight.
     */
    @SuppressWarnings("unused")
    private static class SizedThumbnailBitmap {
		public byte[] mThumbnailData;
        public Bitmap mBitmap;
        public int mThumbnailWidth;
        public int mThumbnailHeight;
    }

    /**
     * Creates a bitmap by either downsampling from the thumbnail in EXIF or the full image.
     * The functions returns a SizedThumbnailBitmap,
     * which contains a downsampled bitmap and the thumbnail data in EXIF if exists.
     */
    public static Bitmap createThumbnailFromEXIF(String filePath, int targetSize,
            int maxPixels, SizedThumbnailBitmap sizedThumbBitmap) {
        if (filePath == null) return null;

        ExifInterface exif = null;
        byte [] thumbData = null;
        try {
            exif = new ExifInterface(filePath);
            if (exif != null) {
                thumbData = exif.getThumbnail();
            }
        } catch (IOException ex) {
        	Log.e(TAG, "", ex);
        }

        BitmapFactory.Options fullOptions = new BitmapFactory.Options();
        BitmapFactory.Options exifOptions = new BitmapFactory.Options();
        int exifThumbWidth = 0;
        int fullThumbWidth = 0;

        // Compute exifThumbWidth.
        if (thumbData != null) {
            exifOptions.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(thumbData, 0, thumbData.length, exifOptions);
            exifOptions.inSampleSize = computeSampleSize(exifOptions, targetSize, maxPixels);
            exifThumbWidth = exifOptions.outWidth / exifOptions.inSampleSize;
        }

        // Compute fullThumbWidth.
        fullOptions.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(filePath, fullOptions);
        fullOptions.inSampleSize = computeSampleSize(fullOptions, targetSize, maxPixels);
        fullThumbWidth = fullOptions.outWidth / fullOptions.inSampleSize;

        // Choose the larger thumbnail as the returning sizedThumbBitmap.
        if (thumbData != null && exifThumbWidth >= fullThumbWidth) {
            int width = exifOptions.outWidth;
            int height = exifOptions.outHeight;
            exifOptions.inJustDecodeBounds = false;
            sizedThumbBitmap.mBitmap = BitmapFactory.decodeByteArray(thumbData, 0,
                    thumbData.length, exifOptions);
            if (sizedThumbBitmap.mBitmap != null) {
                sizedThumbBitmap.mThumbnailData = thumbData;
                sizedThumbBitmap.mThumbnailWidth = width;
                sizedThumbBitmap.mThumbnailHeight = height;
            }
        } else {
            fullOptions.inJustDecodeBounds = false;
            sizedThumbBitmap.mBitmap = BitmapFactory.decodeFile(filePath, fullOptions);
        }
        
        return sizedThumbBitmap.mBitmap;
    }
    
    public static float getBitmapRotate (String filePath) {
		float rtRotate = 0f;
		try {
			ExifInterface exif = new ExifInterface(filePath);
			int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
			switch(orientation) {
			case ExifInterface.ORIENTATION_ROTATE_270:
				rtRotate += 90;
			case ExifInterface.ORIENTATION_ROTATE_180:
				rtRotate += 90;
			case ExifInterface.ORIENTATION_ROTATE_90:
				rtRotate += 90;
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
		return rtRotate;
	}
    
    public static Bitmap createBitmapFromEXIF(String path) {
    	ExifInterface exif;
    	Bitmap b = null;
		try {
			exif = new ExifInterface(path);
			if (exif.hasThumbnail()) {
	    		byte[] thumbBytes = exif.getThumbnail();
	    		b = BitmapFactory.decodeByteArray(thumbBytes, 0, thumbBytes.length);
	    	}
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		return b;
    }
    
    // added by Justin, 2013/04/23
    public static Matrix getRotateAndScaleMatrix(int width, int height, float rotate, float scale) {
		Matrix matrix = new Matrix();
      matrix.preScale(scale, scale);
      matrix.postTranslate(-((width * scale) / 2), -((height * scale) / 2));
      matrix.postRotate(rotate);
      boolean invert = ((rotate / 90) % 2) != 0;
      matrix.postTranslate((invert) ? ((height * scale) / 2) : ((width * scale) / 2), 
      		(invert) ? ((width * scale) / 2) : ((height * scale) / 2));
      
      return matrix;
	}
    
    public static Bitmap getSquareBitmap(String path, int targetSize) {
    	return getSquareBitmap(path, targetSize, true);
    }
    
    public static Bitmap getSquareBitmap(String path, int targetSize, boolean limitation) {
    	if (TextUtils.isEmpty(path) || targetSize <= 0) {
    		return null;
    	}
    	File f = new File(path);
    	if (!f.exists()) {
    		return null;
    	}
    	float imgRotate = getBitmapRotate(path);
    	BitmapFactory.Options opts = new BitmapFactory.Options();
    	opts.inJustDecodeBounds = true;
    	BitmapFactory.decodeFile(path, opts);
    	int imageBase = (opts.outWidth < opts.outHeight) ? opts.outWidth : opts.outHeight;
    	if (limitation && targetSize > imageBase) {
    		targetSize = imageBase;
    	}
    	int inSampleSize = (int)(imageBase /(float)targetSize);
    	inSampleSize = (inSampleSize <= 0) ? 1 : inSampleSize;
    	
//    	android.util.Log.d(TAG, "bitmap size=" + opts.outWidth + "*" + opts.outHeight + ", inSampleSize " + inSampleSize);
//    	long t1 = System.currentTimeMillis();
    	opts.inJustDecodeBounds = false;
    	opts.inSampleSize = inSampleSize;
    	Bitmap b = BitmapFactory.decodeFile(path, opts);
    	if (b == null) {
    		return null;
    	}
//    	android.util.Log.d(TAG, "inSampleSize " + inSampleSize +" decoded bitmap size=" + opts.outWidth + "*" + opts.outHeight + ", takes " + (System.currentTimeMillis() - t1));
    	
    	float newBaseSize = (b.getWidth() < b.getHeight()) ? b.getWidth() : b.getHeight();
    	float scale = targetSize / newBaseSize;
    	
//    	t1 = System.currentTimeMillis();
    	Matrix m = getRotateAndScaleMatrix(b.getWidth(), b.getHeight(), imgRotate, scale);
    	
    	Bitmap scaledBitmap = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
    	if (scaledBitmap != null && scaledBitmap != b) {
    		b.recycle();
    	}
    	b = scaledBitmap;
//    	android.util.Log.i(TAG, "scaled bitmap size " + b.getWidth() + "*" + b.getHeight() + ", takes " + (System.currentTimeMillis() - t1));
    	
//    	t1 = System.currentTimeMillis();
    	Bitmap finalBitmap = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888);
    	
    	Canvas canvas = new Canvas(finalBitmap);
    	int width = b.getWidth();
    	int height = b.getHeight();
    	int left = (width - targetSize) / 2;
    	int top = (height - targetSize) / 2;
    	int right = left + targetSize;
    	int bottom = top + targetSize;
    	Rect src = new Rect(left, top, right, bottom);
    	Rect dst = new Rect(0, 0, targetSize, targetSize);
    	
    	canvas.drawBitmap(b, src, dst, new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG));
    	if (b != null && b != finalBitmap) {
    		b.recycle();
    	}
//    	android.util.Log.i(TAG, "draw bitmap to size " + targetSize + " takes " + (System.currentTimeMillis() - t1));
    	return finalBitmap;
    }
    
    /*public static Bitmap getSquareBitmap(Bitmap b, int targetSize, boolean limitation) {
    	if (b == null) {
    		return null;
    	}
    	int width = b.getWidth();
    	int height = b.getHeight();
    	
    	int imageBase = (width < height) ? width : height;
    	if (limitation && targetSize > imageBase) {
    		targetSize = imageBase;
    	}
    	
    	float scale = targetSize / imageBase;
    	
    	Matrix m = getRotateAndScaleMatrix(b.getWidth(), b.getHeight(), 0, scale);
    	
    	Bitmap scaledBitmap = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
    	if (scaledBitmap != null && scaledBitmap != b) {
    		b.recycle();
    	}
    	b = scaledBitmap;
    	
    	Bitmap finalBitmap = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888);
    	
    	Canvas canvas = new Canvas(finalBitmap);
    	width = b.getWidth();
    	height = b.getHeight();
    	int left = (width - targetSize) / 2;
    	int top = (height - targetSize) / 2;
    	int right = left + targetSize;
    	int bottom = top + targetSize;
    	Rect src = new Rect(left, top, right, bottom);
    	Rect dst = new Rect(0, 0, targetSize, targetSize);
    	
    	canvas.drawBitmap(b, src, dst, new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG));
    	if (b != null && b != finalBitmap) {
    		b.recycle();
    	}
    	
    	return finalBitmap;
    }*/
    
    public static boolean saveBitmap(String cacheBitmapPath, Bitmap bitmap) {
    	if (bitmap == null || bitmap.isRecycled()) {
    		return false;
    	}
    	FileOutputStream stream = null;
		try {
			stream = new FileOutputStream(cacheBitmapPath);
			android.util.Log.i("Compress", "compress bitmap " + cacheBitmapPath);
			return bitmap.compress(CompressFormat.JPEG, 85, stream);
		} catch (Exception e) {
			e.printStackTrace();
			File f = new File(cacheBitmapPath);
			if (f.exists()) {
				f.delete();
			}
		} finally {
			if (stream != null) {
				try {
					stream.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
    	return false;
    }
    
    public static String generateMiniPathString(String cachedDirectory, String path) {
		if (TextUtils.isEmpty(path)) {
			return null;
		}
		File dir = new File(cachedDirectory);
		if (!dir.isDirectory() || !dir.exists()) {
			return null;
		}
		File f = new File(path);
    	long modifiedTime = 0L;
    	if (f.exists()) {
    		modifiedTime = f.lastModified();
    	}
    	StringBuilder b = new StringBuilder();
    	b.append(path);
    	b.append(String.valueOf(modifiedTime));
    	String pathString = b.toString();
    	int hashCode = pathString.hashCode();
    	
    	return cachedDirectory + hashCode;
	}
    
    public static Bitmap scalePhotoToSpecificDimension(String path, int targetWidth, int targetHeight) {
    	if (targetWidth <= 0 || targetHeight <= 0) {
    		throw new IllegalArgumentException("targetWidth or targetHeight can not be less than 0");
    	}
    	int targetBase = (targetWidth > targetHeight) ? targetWidth : targetHeight;
    	BitmapFactory.Options opts = new BitmapFactory.Options();
    	opts.inJustDecodeBounds = true;
    	BitmapFactory.decodeFile(path, opts);
    	int baseSize = (opts.outWidth > opts.outHeight) ? opts.outWidth : opts.outHeight;
    	
    	float scale = baseSize / (float)targetBase;
    	
    	scale = (scale <= 1) ? 1 : scale;
    	opts.inJustDecodeBounds = false;
    	opts.inSampleSize = (int) scale;
    	Bitmap bitmap = BitmapFactory.decodeFile(path, opts);
    	
    	if (bitmap == null) {
    		Log.e(TAG, "file " + path + " can not decoded");
    		return null;
    	}
    	
    	Bitmap canvasBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888) ;
    	Canvas canvas = new Canvas(canvasBitmap);
    	Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
		p.setDither(true);
		
		Rect finalRect = new Rect(0, 0, targetWidth, targetHeight);
		canvas.drawBitmap(bitmap, null, finalRect, p);
		
		if (canvasBitmap != bitmap) {
			bitmap.recycle();
		}
		bitmap = canvasBitmap;
		
		return bitmap;
    }
    
    public static Bitmap decodeBitmapBaseOnLongSide(String path, int targetSize) {
    	BitmapFactory.Options opts = new BitmapFactory.Options();
    	opts.inJustDecodeBounds = true;
    	BitmapFactory.decodeFile(path, opts);
    	int baseSize = (opts.outWidth > opts.outHeight) ? opts.outWidth : opts.outHeight;
    	boolean baseOnWidth = (opts.outWidth > opts.outHeight);
    	float scale = baseSize / (float)targetSize;
    	scale = (scale <= 1) ? 1 : scale;
    	opts.inJustDecodeBounds = false;
    	opts.inSampleSize = (int) scale;
    	Bitmap bitmap = BitmapFactory.decodeFile(path, opts);
    	
    	if (bitmap == null) {
    		Log.e(TAG, "file " + path + " can not decoded");
    		return null;
    	}
    	
    	int currentBase = (baseOnWidth) ? bitmap.getWidth() : bitmap.getHeight();
    	if (currentBase != targetSize) {
    		scale = currentBase / (float)targetSize;
    		int targetLength = (int) (((baseOnWidth) ? bitmap.getHeight() : bitmap.getWidth()) / scale);
    		Bitmap canvasBitmap = (baseOnWidth) ? Bitmap.createBitmap(targetSize, targetLength, Bitmap.Config.ARGB_8888) : Bitmap.createBitmap(targetLength, targetSize, Bitmap.Config.ARGB_8888);
    		Canvas canvas = new Canvas(canvasBitmap);
    		Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
			p.setDither(true);
			Rect finalRect = (baseOnWidth) ? new Rect(0, 0, targetSize, targetLength) : new Rect(0, 0, targetLength, targetSize);
			canvas.drawBitmap(bitmap, null, finalRect, p);
			if (canvasBitmap != bitmap) {
				bitmap.recycle();
			}
			bitmap = canvasBitmap;
    	}
    	
    	return bitmap;
    }
    
    public static Bitmap decodeBitmapToSpecificSize(String path, int targetWidth) {
    	BitmapFactory.Options opts = new BitmapFactory.Options();
    	opts.inJustDecodeBounds = true;
    	BitmapFactory.decodeFile(path, opts);
    	int baseSize = (opts.outWidth > opts.outHeight) ? opts.outWidth : opts.outHeight;
    	float scale = baseSize / (float)targetWidth;
    	scale = (scale <= 1) ? 1 : scale;
    	opts.inJustDecodeBounds = false;
    	opts.inSampleSize = (int) scale;
    	Bitmap bitmap = BitmapFactory.decodeFile(path, opts);
    	
    	if (bitmap == null) {
    		Log.e(TAG, "file " + path + " can not decoded");
    		return null;
    	}
    	
    	int currentWidth = bitmap.getWidth();
    	if (currentWidth != targetWidth) {
    		scale = currentWidth / (float)targetWidth;
    		int targetHeight = (int) (bitmap.getHeight() / scale);
    		Bitmap canvasBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888);
    		Canvas canvas = new Canvas(canvasBitmap);
    		Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
			p.setDither(true);
			canvas.drawBitmap(bitmap, null, new Rect(0, 0, targetWidth, targetHeight), p);
			if (canvasBitmap != bitmap) {
				bitmap.recycle();
			}
			bitmap = canvasBitmap;
    	}
    	
    	return bitmap;
    }
    
}