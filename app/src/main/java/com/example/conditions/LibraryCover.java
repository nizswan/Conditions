package com.example.conditions;

import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.net.Uri;

final class LibraryCover {
    interface Selected { void accept(byte[] photo); }

    static byte[] read(android.content.Context context, Uri uri) throws Exception {
        Bitmap photo;
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            photo = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.getContentResolver(), uri), (decoder, info, source) -> {
                int width = info.getSize().getWidth(), height = info.getSize().getHeight();
                float scale = Math.min(1f, 960f / Math.max(width, height));
                decoder.setTargetSize(Math.max(1, Math.round(width * scale)), Math.max(1, Math.round(height * scale)));
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            });
        } else {
            BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
            try (java.io.InputStream input = context.getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(input, null, options); }
            options.inSampleSize = 1;
            while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 960) options.inSampleSize *= 2;
            options.inJustDecodeBounds = false;
            try (java.io.InputStream input = context.getContentResolver().openInputStream(uri)) { photo = BitmapFactory.decodeStream(input, null, options); }
        }
        if (photo == null) throw new java.io.IOException("Invalid image");
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        photo.compress(Bitmap.CompressFormat.JPEG, 85, bytes); photo.recycle();
        return bytes.toByteArray();
    }

    static Drawable background(byte[] data, android.graphics.drawable.GradientDrawable gradient, float radius) {
        if (data == null) return gradient;
        Bitmap photo = BitmapFactory.decodeByteArray(data, 0, data.length);
        if (photo == null) return gradient;
        gradient.setAlpha(185);
        return new Drawable() {
            final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            @Override public void draw(Canvas canvas) {
                Rect bounds = getBounds();
                if (bounds.isEmpty()) return;
                float scale = Math.max((float) bounds.width() / photo.getWidth(), (float) bounds.height() / photo.getHeight());
                float width = photo.getWidth() * scale, height = photo.getHeight() * scale;
                RectF target = new RectF(bounds.centerX() - width / 2, bounds.centerY() - height / 2,
                        bounds.centerX() + width / 2, bounds.centerY() + height / 2);
                Path clip = new Path(); clip.addRoundRect(new RectF(bounds), radius, radius, Path.Direction.CW);
                canvas.save(); canvas.clipPath(clip); canvas.drawBitmap(photo, null, target, paint);
                gradient.setBounds(bounds); gradient.draw(canvas); canvas.restore();
            }
            @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
            @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
    }
}
