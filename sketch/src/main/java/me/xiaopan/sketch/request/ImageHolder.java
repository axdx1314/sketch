/*
 * Copyright (C) 2013 Peng fei Pan <sky@xiaopan.me>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.xiaopan.sketch.request;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

import me.xiaopan.sketch.Configuration;
import me.xiaopan.sketch.Sketch;
import me.xiaopan.sketch.cache.MemoryCache;
import me.xiaopan.sketch.display.ImageDisplayer;
import me.xiaopan.sketch.drawable.FixedSizeRefBitmapDrawable;
import me.xiaopan.sketch.drawable.RefBitmap;
import me.xiaopan.sketch.drawable.RefBitmapDrawable;
import me.xiaopan.sketch.feature.ExceptionMonitor;
import me.xiaopan.sketch.process.ImageProcessor;
import me.xiaopan.sketch.util.SketchUtils;

// TODO: 16/10/20 支持各种Drawable
public class ImageHolder {
    private int resId;
    private Resize resize;
    private String memoryCacheId;
    private boolean lowQualityImage;
    private boolean forceUseResize;
    private ImageProcessor imageProcessor;

    private RefBitmap refBitmap;

    public ImageHolder(int resId) {
        this.resId = resId;
    }

    public boolean isForceUseResize() {
        return forceUseResize;
    }

    public ImageHolder setForceUseResize(boolean forceUseResize) {
        this.forceUseResize = forceUseResize;
        return this;
    }

    @SuppressWarnings("unused")
    public ImageProcessor getImageProcessor() {
        return imageProcessor;
    }

    public ImageHolder setImageProcessor(ImageProcessor imageProcessor) {
        this.imageProcessor = imageProcessor;
        return this;
    }

    public boolean isLowQualityImage() {
        return lowQualityImage;
    }

    public ImageHolder setLowQualityImage(boolean lowQualityImage) {
        this.lowQualityImage = lowQualityImage;
        return this;
    }

    @SuppressWarnings("unused")
    public int getResId() {
        return resId;
    }

    public Resize getResize() {
        return resize;
    }

    public ImageHolder setResize(Resize resize) {
        this.resize = resize;
        return this;
    }

    protected String generateMemoryCacheId(int resId, Resize resize, boolean forceUseResize, boolean lowQualityImage, ImageProcessor imageProcessor) {
        StringBuilder builder = new StringBuilder();
        builder.append(resId);
        if (resize != null) {
            resize.appendIdentifier("_", builder);
        }
        if (forceUseResize) {
            builder.append("_").append("forceUseResize");
        }
        if (lowQualityImage) {
            builder.append("_").append("lowQualityImage");
        }
        if (imageProcessor != null) {
            imageProcessor.appendIdentifier("_", builder);
        }
        return builder.toString();
    }

    private RefBitmap getRefBitmap(Sketch sketch) {
        if (refBitmap != null && !refBitmap.isRecycled()) {
            return refBitmap;
        }

        // 从内存缓存中取
        if (memoryCacheId == null) {
            memoryCacheId = generateMemoryCacheId(resId, resize, forceUseResize, lowQualityImage, imageProcessor);
        }
        Configuration configuration = sketch.getConfiguration();
        MemoryCache lruMemoryCache = configuration.getPlaceholderImageMemoryCache();
        RefBitmap refBitmap = lruMemoryCache.get(memoryCacheId);
        if (refBitmap != null) {
            if (!refBitmap.isRecycled()) {
                this.refBitmap = refBitmap;
                return this.refBitmap;
            } else {
                lruMemoryCache.remove(memoryCacheId);
            }
        }

        // 创建新的图片
        Bitmap bitmap;
        boolean tempLowQualityImage = this.lowQualityImage;
        if (configuration.isGlobalLowQualityImage()) {
            tempLowQualityImage = true;
        }
        boolean allowRecycle = false;

        Drawable resDrawable = configuration.getContext().getResources().getDrawable(resId);
        if (resDrawable != null && resDrawable instanceof BitmapDrawable) {
            bitmap = ((BitmapDrawable) resDrawable).getBitmap();
        } else {
            // TODO: 16/10/20 改造这个方法，其它类型的图片不需要转成bitmap
            bitmap = SketchUtils.drawableToBitmap(resDrawable, tempLowQualityImage);
            allowRecycle = true;
        }

        if (bitmap != null && !bitmap.isRecycled() && imageProcessor != null) {
            Bitmap newBitmap = null;
            try {
                newBitmap = imageProcessor.process(sketch, bitmap, resize, forceUseResize, tempLowQualityImage);
            } catch (OutOfMemoryError e) {
                e.printStackTrace();
                ExceptionMonitor exceptionMonitor = sketch.getConfiguration().getExceptionMonitor();
                exceptionMonitor.onProcessImageFailed(e, UriScheme.DRAWABLE.createUri(String.valueOf(resId)), imageProcessor);
            }
            if (newBitmap != bitmap) {
                if (allowRecycle) {
                    bitmap.recycle();
                }
                bitmap = newBitmap;
                allowRecycle = true;
            }
        }

        if (bitmap != null && !bitmap.isRecycled()) {
            refBitmap = new RefBitmap(bitmap, memoryCacheId, String.valueOf(resId), bitmap.getWidth(), bitmap.getHeight(), null);
            refBitmap.setAllowRecycle(allowRecycle);
            if (refBitmap.isAllowRecycle()) {
                lruMemoryCache.put(memoryCacheId, refBitmap);
            }
            this.refBitmap = refBitmap;
        }

        return this.refBitmap;
    }

    public Drawable getDrawable(Context context, ImageDisplayer imageDisplayer, FixedSize fixedSize, ImageView.ScaleType scaleType) {
        RefBitmap refBitmap = getRefBitmap(Sketch.with(context));
        if (refBitmap == null || refBitmap.isRecycled()) {
            return null;
        }

        RefBitmapDrawable refBitmapDrawable = new RefBitmapDrawable(refBitmap);
        if (SketchUtils.isFixedSize(imageDisplayer, fixedSize, scaleType)) {
            // 如果使用了TransitionImageDisplayer并且ImageVie是固定大小并且ScaleType是CENT_CROP那么就需要根据ImageVie的固定大小来裁剪loadingImage
            return new FixedSizeRefBitmapDrawable(refBitmapDrawable, fixedSize);
        } else {
            return refBitmapDrawable;
        }
    }
}