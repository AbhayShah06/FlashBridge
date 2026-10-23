package com.abhayshah.flashbridge;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

/** Adapted from LocalMusic's dismiss gesture for the pairing and file panels. */
final class SwipeDismissLayout extends FrameLayout {
    private final Runnable dismiss;
    private float startX, startY;
    private long startTime;
    private boolean dragging, blocked, closing;

    SwipeDismissLayout(Context context, Runnable dismiss) {
        super(context);
        this.dismiss = dismiss;
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (closing) return true;
        if (action == MotionEvent.ACTION_DOWN) {
            animate().cancel();
            setTranslationX(0);
            startX = event.getRawX(); startY = event.getRawY(); startTime = event.getEventTime();
            dragging = false;
            blocked = protectedTarget(this, event.getX(), event.getY());
        }
        if (event.getPointerCount() != 1) blocked = true;
        float dx = event.getRawX() - startX, dy = event.getRawY() - startY;
        if (!blocked && !dragging && action == MotionEvent.ACTION_MOVE
                && Math.abs(dx) > dp(24) && Math.abs(dx) > Math.abs(dy) * 1.7f) {
            MotionEvent cancel = MotionEvent.obtain(event);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            super.dispatchTouchEvent(cancel);
            cancel.recycle();
            setLayerType(View.LAYER_TYPE_HARDWARE, null);
            dragging = true;
        }
        if (dragging) {
            setTranslationX(dx);
            if (action == MotionEvent.ACTION_UP) {
                dragging = false;
                float speed = Math.abs(dx) / Math.max(1, event.getEventTime() - startTime);
                if (Math.abs(dx) > Math.max(dp(64), getWidth() * .22f) || (Math.abs(dx) > dp(32) && speed > dp(1))) {
                    closing = true;
                    animate().translationX(Math.copySign(Math.max(getWidth(), dp(320)), dx))
                            .setDuration(180).setInterpolator(new DecelerateInterpolator())
                            .withEndAction(dismiss).start();
                } else restore();
            } else if (action == MotionEvent.ACTION_CANCEL) {
                dragging = false;
                restore();
            }
            return true;
        }
        return super.dispatchTouchEvent(event);
    }

    private void restore() {
        animate().translationX(0).setDuration(220).setInterpolator(new OvershootInterpolator(1.2f))
                .withEndAction(() -> setLayerType(View.LAYER_TYPE_NONE, null)).start();
    }

    private boolean protectedTarget(View view, float x, float y) {
        if (view instanceof EditText || view instanceof SeekBar || view instanceof HorizontalScrollView
                || (view instanceof TextView && ((TextView) view).isTextSelectable())) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                View child = group.getChildAt(i);
                float cx = x + view.getScrollX() - child.getLeft(), cy = y + view.getScrollY() - child.getTop();
                if (child.getVisibility() == View.VISIBLE && cx >= 0 && cy >= 0 && cx < child.getWidth() && cy < child.getHeight())
                    return protectedTarget(child, cx, cy);
            }
        }
        return false;
    }

    private float dp(int value) { return value * getResources().getDisplayMetrics().density; }
}
