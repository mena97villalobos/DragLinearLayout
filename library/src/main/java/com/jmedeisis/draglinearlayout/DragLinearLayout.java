package com.jmedeisis.draglinearlayout;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.LayoutTransition;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.Log;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewTreeObserver;
import android.view.ViewTreeObserver.OnPreDrawListener;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

/**
 * A LinearLayout that supports children Views that can be dragged and swapped around.
 * See {@link #addDragView(android.view.View, android.view.View)},
 * {@link #addDragView(android.view.View, android.view.View, int)},
 * {@link #setViewDraggable(android.view.View, android.view.View)}, and
 * {@link #removeDragView(android.view.View)}.
 * <p/>
 * Currently, no error-checking is done on standard {@link #addView(android.view.View)} and
 * {@link #removeView(android.view.View)} calls, so avoid using these with children previously
 * declared as draggable to prevent memory leaks and/or subtle bugs. Pull requests welcome!
 */
public class DragLinearLayout extends LinearLayout {
    /** By Bryan Mena
     * Changes the child's view background when being drag
     */
    public static int draggingColor = Color.argb(140, 85, 85, 85);
    /**
     * Child's view initial background color, use to restore the background color when drag event finished
     */
    public static int startColor = Color.WHITE;
    private static final String LOG_TAG = DragLinearLayout.class.getSimpleName();
    private static final long NOMINAL_SWITCH_DURATION = 150;
    private static final long MIN_SWITCH_DURATION = NOMINAL_SWITCH_DURATION;
    private static final long MAX_SWITCH_DURATION = NOMINAL_SWITCH_DURATION * 2;
    private static final float NOMINAL_DISTANCE = 20;
    private static final int INVALID_POINTER_ID = -1;
    private static final int DEFAULT_SCROLL_SENSITIVE_AREA_HEIGHT_DP = 48;
    private static final int MAX_DRAG_SCROLL_SPEED = 16;
    private final float nominalDistanceScaled;
    /**
     * Mapping from child index to drag-related info container.
     * Presence of mapping implies the child can be dragged, and is considered for swaps with the
     * currently dragged item.
     */
    private final SparseArray<DraggableChild> draggableChildren;
    /**
     * The currently dragged item, if {@link DragItem#detecting}.
     */
    private final DragItem draggedItem;
    private final int slop;
    /**
     * The shadow to be drawn above the {@link #draggedItem}.
     */
    private final Drawable dragTopShadowDrawable;
    /**
     * The shadow to be drawn below the {@link #draggedItem}.
     */
    private final Drawable dragBottomShadowDrawable;
    private final int dragShadowHeight;
    private OnViewSwapListener swapListener;
    private LayoutTransition layoutTransition;
    private int downY = -1;
    private int activePointerId = INVALID_POINTER_ID;
    /**
     * See {@link #setContainerScrollView(android.widget.ScrollView)}.
     */
    private ScrollView containerScrollView;
    private int scrollSensitiveAreaHeight;
    private Runnable dragUpdater;

    public DragLinearLayout(Context context) {
        this(context, null);
    }

    public DragLinearLayout(Context context, AttributeSet attrs) {
        super(context, attrs);

        setOrientation(LinearLayout.VERTICAL);

        draggableChildren = new SparseArray<>();

        draggedItem = new DragItem();
        ViewConfiguration vc = ViewConfiguration.get(context);
        slop = vc.getScaledTouchSlop();

        final Resources resources = getResources();
        dragTopShadowDrawable = ContextCompat.getDrawable(context, R.drawable.ab_solid_shadow_holo_flipped);
        dragBottomShadowDrawable = ContextCompat.getDrawable(context, R.drawable.ab_solid_shadow_holo);
        dragShadowHeight = resources.getDimensionPixelSize(R.dimen.downwards_drop_shadow_height);

        TypedArray a = context.getTheme().obtainStyledAttributes(attrs, R.styleable.DragLinearLayout, 0, 0);
        try {
            scrollSensitiveAreaHeight = a.getDimensionPixelSize(R.styleable.DragLinearLayout_scrollSensitiveHeight,
                    (int) (DEFAULT_SCROLL_SENSITIVE_AREA_HEIGHT_DP * resources.getDisplayMetrics().density + 0.5f));
        } finally {
            a.recycle();
        }

        nominalDistanceScaled = (int) (NOMINAL_DISTANCE * resources.getDisplayMetrics().density + 0.5f);
    }

    /**
     * By Ken Perlin. See <a href="http://en.wikipedia.org/wiki/Smoothstep">Smoothstep - Wikipedia</a>.
     */
    private static float smootherStep(float edge1, float edge2, float val) {
        val = Math.max(0, Math.min((val - edge1) / (edge2 - edge1), 1));
        return val * val * val * (val * (val * 6 - 15) + 10);
    }

    /**
     * @return a bitmap showing a screenshot of the view passed in.
     */
    private static Bitmap getBitmapFromView(View view) {
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        view.draw(canvas);
        return bitmap;
    }

    @Override
    public void setOrientation(int orientation) {
        // enforce VERTICAL orientation; remove if HORIZONTAL support is ever added
        if (LinearLayout.HORIZONTAL == orientation) {
            throw new IllegalArgumentException("DragLinearLayout must be VERTICAL.");
        }
        super.setOrientation(orientation);
    }

    /**
     * Calls {@link #addView(android.view.View)} followed by {@link #setViewDraggable(android.view.View, android.view.View)}.
     */
    public void addDragView(View child, View dragHandle) {
        addView(child);
        setViewDraggable(child, dragHandle);
    }

    /**
     * Calls {@link #addView(android.view.View, int)} followed by
     * {@link #setViewDraggable(android.view.View, android.view.View)} and correctly updates the
     * drag-ability state of all existing views.
     */
    @SuppressWarnings("unused")
    public void addDragView(View child, View dragHandle, int index) {
        addView(child, index);

        // update drag-able children mappings
        final int numMappings = draggableChildren.size();
        for (int i = numMappings - 1; i >= 0; i--) {
            final int key = draggableChildren.keyAt(i);
            if (key >= index) {
                draggableChildren.put(key + 1, draggableChildren.get(key));
            }
        }

        setViewDraggable(child, dragHandle);
    }

    /**
     * Makes the child a candidate for dragging. Must be an existing child of this layout.
     */
    public void setViewDraggable(View child, View dragHandle) {
        if (null == child || null == dragHandle) {
            throw new IllegalArgumentException(
                    "Draggable children and their drag handles must not be null.");
        }

        if (this == child.getParent()) {
            dragHandle.setOnTouchListener(new DragHandleOnTouchListener(child));
            draggableChildren.put(indexOfChild(child), new DraggableChild());
        } else {
            Log.e(LOG_TAG, child + " is not a child, cannot make draggable.");
        }
    }

    /**
     * Calls {@link #removeView(android.view.View)} and correctly updates the drag-ability state of
     * all remaining views.
     */
    @SuppressWarnings("UnusedDeclaration")
    public void removeDragView(View child) {
        if (this == child.getParent()) {
            final int index = indexOfChild(child);
            removeView(child);

            // update drag-able children mappings
            final int mappings = draggableChildren.size();
            for (int i = 0; i < mappings; i++) {
                final int key = draggableChildren.keyAt(i);
                if (key >= index) {
                    DraggableChild next = draggableChildren.get(key + 1);
                    if (null == next) {
                        draggableChildren.delete(key);
                    } else {
                        draggableChildren.put(key, next);
                    }
                }
            }
        }
    }

    @Override
    public void removeAllViews() {
        super.removeAllViews();
        draggableChildren.clear();
    }

    /**
     * If this layout is within a {@link android.widget.ScrollView}, register it here so that it
     * can be scrolled during item drags.
     */
    public void setContainerScrollView(ScrollView scrollView) {
        this.containerScrollView = scrollView;
    }

    @SuppressWarnings("UnusedDeclaration")
    public int getScrollSensitiveHeight() {
        return scrollSensitiveAreaHeight;
    }

    /**
     * Sets the height from upper / lower edge at which a container {@link android.widget.ScrollView},
     * if one is registered via {@link #setContainerScrollView(android.widget.ScrollView)},
     * is scrolled.
     */
    @SuppressWarnings("UnusedDeclaration")
    public void setScrollSensitiveHeight(int height) {
        this.scrollSensitiveAreaHeight = height;
    }

    /**
     * See {@link com.jmedeisis.draglinearlayout.DragLinearLayout.OnViewSwapListener}.
     */
    public void setOnViewSwapListener(OnViewSwapListener swapListener) {
        this.swapListener = swapListener;
    }

    /**
     * A linear relationship b/w distance and duration, bounded.
     */
    private long getTranslateAnimationDuration(float distance) {
        return Math.min(MAX_SWITCH_DURATION, Math.max(MIN_SWITCH_DURATION,
                (long) (NOMINAL_SWITCH_DURATION * Math.abs(distance) / nominalDistanceScaled)));
    }

    /**
     * Initiates a new {@link #draggedItem} unless the current one is still
     * {@link com.jmedeisis.draglinearlayout.DragLinearLayout.DragItem#detecting}.
     */
    private void startDetectingDrag(View child) {
        if (draggedItem.detecting)
            return; // existing drag in process, only one at a time is allowed

        final int position = indexOfChild(child);

        // complete any existing animations, both for the newly selected child and the previous dragged one
        draggableChildren.get(position).endExistingAnimation();

        draggedItem.startDetectingOnPossibleDrag(child, position);
        if (containerScrollView != null) {
            containerScrollView.requestDisallowInterceptTouchEvent(true);
        }
    }

    private void startDrag() {
        // remove layout transition, it conflicts with drag animation
        // we will restore it after drag animation end, see onDragStop()
        layoutTransition = getLayoutTransition();
        if (layoutTransition != null) {
            setLayoutTransition(null);
        }

        draggedItem.onDragStart();
        requestDisallowInterceptTouchEvent(true);
    }

    /**
     * Animates the dragged item to its final resting position.
     */
    private void onDragStop() {

        int totalDragOffset = draggedItem.totalDragOffset;
        int targetTopOffset = draggedItem.targetTopOffset;

        draggedItem.settleAnimation =
                ValueAnimator
                        .ofFloat(totalDragOffset, totalDragOffset - targetTopOffset)
                        .setDuration(getTranslateAnimationDuration(targetTopOffset));

        draggedItem.settleAnimation.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                if (!draggedItem.detecting) return; // already stopped

                draggedItem.setTotalOffset(((Float) animation.getAnimatedValue()).intValue());

                final int shadowAlpha = (int) ((1 - animation.getAnimatedFraction()) * 255);
                if (null != dragTopShadowDrawable) dragTopShadowDrawable.setAlpha(shadowAlpha);
                dragBottomShadowDrawable.setAlpha(shadowAlpha);
                invalidate();
            }
        });
        draggedItem.settleAnimation.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                draggedItem.onDragStop();
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (!draggedItem.detecting) {
                    return; // already stopped
                }

                draggedItem.settleAnimation = null;
                draggedItem.view.setBackgroundColor(startColor);
                draggedItem.stopDetecting();

                if (null != dragTopShadowDrawable) dragTopShadowDrawable.setAlpha(255);
                dragBottomShadowDrawable.setAlpha(255);

                // restore layout transition
                if (layoutTransition != null && getLayoutTransition() == null) {
                    setLayoutTransition(layoutTransition);
                }
            }
        });
        draggedItem.settleAnimation.start();
    }

    /**
     * Updates the dragged item with the given total offset from its starting position.
     * Evaluates and executes draggable view swaps.
     */
    private void onDrag(final int offset) {
        draggedItem.setTotalOffset(offset);
        invalidate();

        int currentTop = draggedItem.startTop + draggedItem.totalDragOffset;

        handleContainerScroll(currentTop);

        int belowPosition = nextDraggablePosition(draggedItem.position);
        int abovePosition = previousDraggablePosition(draggedItem.position);

        View belowView = getChildAt(belowPosition);
        View aboveView = getChildAt(abovePosition);

        final boolean isBelow = (belowView != null) &&
                (currentTop + draggedItem.height > belowView.getTop() + belowView.getHeight() / 2);
        final boolean isAbove = (aboveView != null) &&
                (currentTop < aboveView.getTop() + aboveView.getHeight() / 2);

        if (isBelow || isAbove) {
            final View switchView = isBelow ? belowView : aboveView;

            // swap elements
            final int originalPosition = draggedItem.position;
            final int switchPosition = isBelow ? belowPosition : abovePosition;

            draggableChildren.get(switchPosition).cancelExistingAnimation();
            final float switchViewStartY = switchView.getY();

            if (null != swapListener) {
                swapListener.onSwap(draggedItem.view, draggedItem.position, switchView, switchPosition);
            }

            if (isBelow) {
                removeViewAt(originalPosition);
                removeViewAt(switchPosition - 1);

                addView(belowView, originalPosition);
                addView(draggedItem.view, switchPosition);
            } else {
                removeViewAt(switchPosition);
                removeViewAt(originalPosition - 1);

                addView(draggedItem.view, switchPosition);
                addView(aboveView, originalPosition);
            }
            draggedItem.position = switchPosition;

            final ViewTreeObserver switchViewObserver = switchView.getViewTreeObserver();
            switchViewObserver.addOnPreDrawListener(new OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    switchViewObserver.removeOnPreDrawListener(this);

                    final ObjectAnimator switchAnimator = ObjectAnimator.ofFloat(switchView, "y",
                            switchViewStartY, switchView.getTop())
                            .setDuration(getTranslateAnimationDuration(switchView.getTop() - switchViewStartY));
                    switchAnimator.addListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationStart(Animator animation) {
                            draggableChildren.get(originalPosition).swapAnimation = switchAnimator;
                        }

                        @Override
                        public void onAnimationEnd(Animator animation) {
                            draggableChildren.get(originalPosition).swapAnimation = null;
                        }
                    });
                    switchAnimator.start();

                    return true;
                }
            });

            final ViewTreeObserver observer = draggedItem.view.getViewTreeObserver();
            observer.addOnPreDrawListener(new OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    observer.removeOnPreDrawListener(this);
                    draggedItem.updateTargetTop();
                    if (draggedItem.settling()) {
                        Log.d(LOG_TAG, "Updating settle animation");
                        draggedItem.settleAnimation.removeAllListeners();
                        draggedItem.settleAnimation.cancel();
                        onDragStop();
                    }
                    return true;
                }
            });
        }
    }

    private int previousDraggablePosition(int position) {
        int startIndex = draggableChildren.indexOfKey(position);
        if (startIndex < 1 || startIndex > draggableChildren.size()) return -1;
        return draggableChildren.keyAt(startIndex - 1);
    }

    private int nextDraggablePosition(int position) {
        int startIndex = draggableChildren.indexOfKey(position);
        if (startIndex < -1 || startIndex > draggableChildren.size() - 2) return -1;
        return draggableChildren.keyAt(startIndex + 1);
    }

    private void handleContainerScroll(final int currentTop) {
        if (null != containerScrollView) {
            final int startScrollY = containerScrollView.getScrollY();
            final int absTop = getTop() - startScrollY + currentTop;
            final int height = containerScrollView.getHeight();

            final int delta;

            if (absTop < scrollSensitiveAreaHeight) {
                delta = (int) (-MAX_DRAG_SCROLL_SPEED * smootherStep(scrollSensitiveAreaHeight, 0, absTop));
            } else if (absTop > height - scrollSensitiveAreaHeight) {
                delta = (int) (MAX_DRAG_SCROLL_SPEED * smootherStep(height - scrollSensitiveAreaHeight, height, absTop));
            } else {
                delta = 0;
            }

            containerScrollView.removeCallbacks(dragUpdater);
            containerScrollView.smoothScrollBy(0, delta);
            dragUpdater = new Runnable() {
                @Override
                public void run() {
                    if (draggedItem.dragging && startScrollY != containerScrollView.getScrollY()) {
                        onDrag(draggedItem.totalDragOffset + delta);
                    }
                }
            };
            containerScrollView.post(dragUpdater);
        }
    }

    @Override
    protected void dispatchDraw(@NonNull Canvas canvas) {
        super.dispatchDraw(canvas);

        if (draggedItem.detecting && (draggedItem.dragging || draggedItem.settling())) {
            canvas.save();
            canvas.translate(0, draggedItem.totalDragOffset);
            draggedItem.viewDrawable.draw(canvas);

            final int left = draggedItem.viewDrawable.getBounds().left;
            final int right = draggedItem.viewDrawable.getBounds().right;
            final int top = draggedItem.viewDrawable.getBounds().top;
            final int bottom = draggedItem.viewDrawable.getBounds().bottom;

            dragBottomShadowDrawable.setBounds(left, bottom, right, bottom + dragShadowHeight);
            dragBottomShadowDrawable.draw(canvas);

            if (null != dragTopShadowDrawable) {
                dragTopShadowDrawable.setBounds(left, top - dragShadowHeight, right, top);
                dragTopShadowDrawable.draw(canvas);
            }

            canvas.restore();
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN: {
                if (draggedItem.detecting)
                    return false; // an existing item is (likely) settling
                downY = (int) event.getY(0);
                activePointerId = event.getPointerId(0);
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (!draggedItem.detecting) return false;
                if (INVALID_POINTER_ID == activePointerId) break;
                final int pointerIndex = event.findPointerIndex(activePointerId);
                final float y = event.getY(pointerIndex);
                final float dy = y - downY;
                if (Math.abs(dy) > slop) {
                    startDrag();
                    return true;
                }
                return false;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                final int pointerIndex = event.getActionIndex();
                final int pointerId = event.getPointerId(pointerIndex);

                if (pointerId != activePointerId)
                    break; // if active pointer, fall through and cancel!
            }
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP: {
                onTouchEnd();
                if (draggedItem.detecting) {
                    draggedItem.view.setBackgroundColor(startColor);
                    draggedItem.stopDetecting();
                }
                break;
            }
        }

        return false;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(@NonNull MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN: {
                if (!draggedItem.detecting || draggedItem.settling()) return false;
                startDrag();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (!draggedItem.dragging) break;
                if (INVALID_POINTER_ID == activePointerId) break;

                int pointerIndex = event.findPointerIndex(activePointerId);
                int lastEventY = (int) event.getY(pointerIndex);
                int deltaY = lastEventY - downY;

                onDrag(deltaY);
                return true;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                final int pointerIndex = event.getActionIndex();
                final int pointerId = event.getPointerId(pointerIndex);

                if (pointerId != activePointerId)
                    break; // if active pointer, fall through and cancel!
            }
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP: {
                onTouchEnd();

                if (draggedItem.dragging) {
                    onDragStop();
                } else if (draggedItem.detecting) {
                    draggedItem.view.setBackgroundColor(startColor);
                    draggedItem.stopDetecting();
                }
                return true;
            }
        }
        return false;
    }

    /*
     * Note regarding touch handling:
     * In general, we have three cases -
     * 1) User taps outside any children.
     *      #onInterceptTouchEvent receives DOWN
     *      #onTouchEvent receives DOWN
     *          draggedItem.detecting == false, we return false and no further events are received
     * 2) User taps on non-interactive drag handle / child, e.g. TextView or ImageView.
     *      #onInterceptTouchEvent receives DOWN
     *      DragHandleOnTouchListener (attached to each draggable child) #onTouch receives DOWN
     *      #startDetectingDrag is called, draggedItem is now detecting
     *      view does not handle touch, so our #onTouchEvent receives DOWN
     *          draggedItem.detecting == true, we #startDrag() and proceed to handle the drag
     * 3) User taps on interactive drag handle / child, e.g. Button.
     *      #onInterceptTouchEvent receives DOWN
     *      DragHandleOnTouchListener (attached to each draggable child) #onTouch receives DOWN
     *      #startDetectingDrag is called, draggedItem is now detecting
     *      view handles touch, so our #onTouchEvent is not called yet
     *      #onInterceptTouchEvent receives ACTION_MOVE
     *      if dy > touch slop, we assume user wants to drag and intercept the event
     *      #onTouchEvent receives further ACTION_MOVE events, proceed to handle the drag
     *
     * For cases 2) and 3), lifting the active pointer at any point in the sequence of events
     * triggers #onTouchEnd and the draggedItem, if detecting, is #stopDetecting.
     */
    private void onTouchEnd() {
        downY = -1;
        activePointerId = INVALID_POINTER_ID;
    }

    private BitmapDrawable getDragDrawable(View view) {
        int top = view.getTop();
        int left = view.getLeft();

        Bitmap bitmap = getBitmapFromView(view);

        BitmapDrawable drawable = new BitmapDrawable(getResources(), bitmap);

        drawable.setBounds(new Rect(left, top, left + view.getWidth(), top + view.getHeight()));

        return drawable;
    }

    /**
     * Use with {@link com.jmedeisis.draglinearlayout.DragLinearLayout#setOnViewSwapListener(com.jmedeisis.draglinearlayout.DragLinearLayout.OnViewSwapListener)}
     * to listen for draggable view swaps.
     */
    public interface OnViewSwapListener {
        /**
         * Invoked right before the two items are swapped due to a drag event.
         * After the swap, the firstView will be in the secondPosition, and vice versa.
         * <p/>
         * No guarantee is made as to which of the two has a lesser/greater position.
         */
        void onSwap(View firstView, int firstPosition, View secondView, int secondPosition);
    }

    /**
     * Holds state information about the currently dragged item.
     * <p/>
     * Rough lifecycle:
     * <li>#startDetectingOnPossibleDrag - #detecting == true</li>
     * <li>     if drag is recognised, #onDragStart - #dragging == true</li>
     * <li>     if drag ends, #onDragStop - #dragging == false, #settling == true</li>
     * <li>if gesture ends without drag, or settling finishes, #stopDetecting - #detecting == false</li>
     */
    class DragItem {
        private View view;
        private int startVisibility;
        private BitmapDrawable viewDrawable;
        private int position;
        private int startTop;
        private int height;
        private int totalDragOffset;
        private int targetTopOffset;
        private ValueAnimator settleAnimation;
        private boolean detecting;
        private boolean dragging;

        DragItem() {
            stopDetecting();
        }

        void startDetectingOnPossibleDrag(final View view, final int position) {
            this.view = view;
            this.startVisibility = view.getVisibility();
            this.viewDrawable = getDragDrawable(view);
            this.position = position;
            this.startTop = view.getTop();
            this.height = view.getHeight();
            this.totalDragOffset = 0;
            this.targetTopOffset = 0;
            this.settleAnimation = null;
            this.detecting = true;
        }

        void onDragStart() {
            view.setVisibility(View.INVISIBLE);
            this.dragging = true;
        }

        void setTotalOffset(int offset) {
            totalDragOffset = offset;
            updateTargetTop();
        }

        void updateTargetTop() {
            targetTopOffset = startTop - view.getTop() + totalDragOffset;
        }

        void onDragStop() {
            this.dragging = false;
        }

        boolean settling() {
            return null != settleAnimation;
        }

        void stopDetecting() {
            this.detecting = false;
            if (null != view) {
                view.setVisibility(startVisibility);
            }
            view = null;
            startVisibility = -1;
            viewDrawable = null;
            position = -1;
            startTop = -1;
            height = -1;
            totalDragOffset = 0;
            targetTopOffset = 0;
            if (null != settleAnimation) settleAnimation.end();
            settleAnimation = null;
        }

    }

    private class DraggableChild {
        /**
         * If non-null, a reference to an on-going position animation.
         */
        private ValueAnimator swapAnimation;

        void endExistingAnimation() {
            if (null != swapAnimation) swapAnimation.end();
        }

        void cancelExistingAnimation() {
            if (null != swapAnimation) swapAnimation.cancel();
        }
    }

    private class DragHandleOnTouchListener implements View.OnTouchListener {
        private final View view;

        DragHandleOnTouchListener(final View view) {
            this.view = view;
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            if (MotionEvent.ACTION_DOWN == event.getAction()) {
                view.setBackgroundColor(draggingColor);
                startDetectingDrag(view);
            }
            return false;
        }
    }


}
