/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ch.deletescape.lawnchair;

import android.animation.ObjectAnimator;
import android.content.ComponentName;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.content.res.Resources.Theme;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Region;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.support.annotation.NonNull;
import android.support.v4.graphics.ColorUtils;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Property;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewDebug;
import android.view.ViewParent;
import android.widget.TextView;

import java.text.NumberFormat;

import ch.deletescape.lawnchair.IconCache.IconLoadRequest;
import ch.deletescape.lawnchair.badge.BadgeInfo;
import ch.deletescape.lawnchair.badge.BadgeRenderer;
import ch.deletescape.lawnchair.folder.FolderIcon;
import ch.deletescape.lawnchair.graphics.IconPalette;
import ch.deletescape.lawnchair.model.PackageItemInfo;
import ch.deletescape.lawnchair.pixelify.ClockIconDrawable;

/**
 * TextView that draws a bubble behind the text. We cannot use a LineBackgroundSpan
 * because we want to make the bubble taller than the text and TextView's clip is
 * too aggressive.
 */
public class BubbleTextView extends TextView {

    private static final Property<BubbleTextView, Float> BADGE_SCALE_PROPERTY
            = new Property<BubbleTextView, Float>(Float.TYPE, "badgeScale") {
        @Override
        public Float get(BubbleTextView bubbleTextView) {
            return bubbleTextView.mBadgeScale;
        }

        @Override
        public void set(BubbleTextView bubbleTextView, Float value) {
            bubbleTextView.mBadgeScale = value;
            bubbleTextView.invalidate();
        }
    };

    public static final Property<BubbleTextView, Integer> TEXT_ALPHA_PROPERTY
            = new Property<BubbleTextView, Integer>(Integer.class, "textAlpha") {
        @Override
        public Integer get(BubbleTextView bubbleTextView) {
            return bubbleTextView.getTextAlpha();
        }

        @Override
        public void set(BubbleTextView bubbleTextView, Integer alpha) {
            bubbleTextView.setTextAlpha(alpha);
        }
    };
    private boolean mHideText;
    private BadgeInfo mBadgeInfo;
    private BadgeRenderer mBadgeRenderer;
    private IconPalette mBadgePalette;
    private float mBadgeScale;
    private boolean mForceHideBadge;
    private Rect mTempIconBounds;
    private Point mTempSpaceForBadgeOffset;
    private IconPalette mIconPalette;


    private static SparseArray<Theme> sPreloaderThemes = new SparseArray<>(2);

    // Dimensions in DP
    private static final float AMBIENT_SHADOW_RADIUS = 2.5f;
    private static final float KEY_SHADOW_RADIUS = 1f;
    private static final float KEY_SHADOW_OFFSET = 0.5f;
    private static final int AMBIENT_SHADOW_COLOR = 0x33000000;
    private static final int KEY_SHADOW_COLOR = 0x66000000;

    private static final int DISPLAY_WORKSPACE = 0;
    private static final int DISPLAY_ALL_APPS = 1;
    private static final int DISPLAY_FOLDER = 2;

    private final Launcher mLauncher;
    private Drawable mIcon;
    private final boolean mCenterVertically;
    private final Drawable mBackground;
    private final CheckLongPressHelper mLongPressHelper;
    private final HolographicOutlineHelper mOutlineHelper;
    private final StylusEventHelper mStylusEventHelper;

    private boolean mBackgroundSizeChanged;

    private Bitmap mPressedBackground;

    private float mSlop;

    private final boolean mDeferShadowGenerationOnTouch;
    private final boolean mCustomShadowsEnabled;
    private final boolean mDisablePressedState;
    private final boolean mShadowsDisabled;
    private final boolean mLayoutHorizontal;
    private final int mIconSize;
    @ViewDebug.ExportedProperty(category = "launcher")
    private int mTextColor;
    private boolean mIsIconVisible = true;

    @ViewDebug.ExportedProperty(category = "launcher")
    private boolean mStayPressed;
    @ViewDebug.ExportedProperty(category = "launcher")
    private boolean mIgnorePressedStateChange;
    @ViewDebug.ExportedProperty(category = "launcher")
    private boolean mDisableRelayout = false;

    private IconLoadRequest mIconLoadRequest;

    public BubbleTextView(Context context) {
        this(context, null, 0);
    }

    public BubbleTextView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BubbleTextView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        this.mTempSpaceForBadgeOffset = new Point();
        this.mTempIconBounds = new Rect();
        mLauncher = Launcher.getLauncher(context);
        DeviceProfile grid = mLauncher.getDeviceProfile();

        TypedArray a = context.obtainStyledAttributes(attrs,
                R.styleable.BubbleTextView, defStyle, 0);
        mCustomShadowsEnabled = a.getBoolean(R.styleable.BubbleTextView_customShadows, true);
        mDisablePressedState = a.getBoolean(R.styleable.BubbleTextView_disablePressedState, false);
        mShadowsDisabled = a.getBoolean(R.styleable.BubbleTextView_disableShadows, false);
        mLayoutHorizontal = a.getBoolean(R.styleable.BubbleTextView_layoutHorizontal, false);
        mDeferShadowGenerationOnTouch =
                a.getBoolean(R.styleable.BubbleTextView_deferShadowGeneration, false);

        int display = a.getInteger(R.styleable.BubbleTextView_iconDisplay, DISPLAY_WORKSPACE);
        int defaultIconSize = grid.iconSizePx;
        if (display == DISPLAY_WORKSPACE) {
            mHideText = Utilities.getPrefs(context).getHideAppLabels();
            setTextSize(TypedValue.COMPLEX_UNIT_PX, mHideText ? 0 : grid.iconTextSizePx);
            setTextColor(Utilities.getPrefs(context).getWorkSpaceLabelColor());
        } else if (display == DISPLAY_ALL_APPS) {
            mHideText = Utilities.getPrefs(context).getHideAllAppsAppLabels();
            setTextSize(TypedValue.COMPLEX_UNIT_PX, mHideText ? 0 : grid.allAppsIconTextSizePx);
            setCompoundDrawablePadding(grid.allAppsIconDrawablePaddingPx);
            defaultIconSize = grid.allAppsIconSizePx;
        } else if (display == DISPLAY_FOLDER) {
            mHideText = Utilities.getPrefs(context).getHideAppLabels();
            setTextSize(TypedValue.COMPLEX_UNIT_PX, mHideText ? 0 : grid.iconTextSizePx);
            setCompoundDrawablePadding(grid.folderChildDrawablePaddingPx);
        }
        mCenterVertically = a.getBoolean(R.styleable.BubbleTextView_centerVertically, false);

        mIconSize = a.getDimensionPixelSize(R.styleable.BubbleTextView_iconSizeOverride,
                defaultIconSize);
        a.recycle();

        if (Utilities.getPrefs(context).getIconLabelsInTwoLines()) {
            setMaxLines(2);
            setEllipsize(TextUtils.TruncateAt.END);
            setHorizontallyScrolling(false);
        }

        if (mCustomShadowsEnabled) {
            // Draw the background itself as the parent is drawn twice.
            mBackground = getBackground();
            setBackground(null);

            // Set shadow layer as the larger shadow to that the textView does not clip the shadow.
            float density = getResources().getDisplayMetrics().density;
            setShadowLayer(density * AMBIENT_SHADOW_RADIUS, 0, 0, AMBIENT_SHADOW_COLOR);
        } else {
            mBackground = null;
        }

        mLongPressHelper = new CheckLongPressHelper(this);
        mStylusEventHelper = new StylusEventHelper(new SimpleOnStylusPressListener(this), this);

        mOutlineHelper = HolographicOutlineHelper.obtain(getContext());
        setAccessibilityDelegate(mLauncher.getAccessibilityDelegate());
    }

    public void applyPromiseState(boolean z) {
        if (getTag() instanceof ShortcutInfo) {
            CharSequence string;
            ShortcutInfo shortcutInfo = (ShortcutInfo) getTag();
            int installProgress = shortcutInfo.isPromise() ? shortcutInfo.hasStatusFlag(4) ? shortcutInfo.getInstallProgress() : 0 : 100;
            if (installProgress > 0) {
                string = getContext().getString(R.string.app_downloading_title, shortcutInfo.title, NumberFormat.getPercentInstance().format(((double) installProgress) * 0.01d));
            } else {
                string = getContext().getString(R.string.app_waiting_download_title, shortcutInfo.title);
            }
            setContentDescription(string);
            if (this.mIcon != null) {
                PreloadIconDrawable preloadIconDrawable;
                if (this.mIcon instanceof PreloadIconDrawable) {
                    preloadIconDrawable = (PreloadIconDrawable) this.mIcon;
                } else {
                    preloadIconDrawable = new PreloadIconDrawable(new FastBitmapDrawable(shortcutInfo.iconBitmap), getPreloaderTheme());
                    setIcon(preloadIconDrawable);
                }
                preloadIconDrawable.setLevel(installProgress);
                if (z) {
                    preloadIconDrawable.maybePerformFinishedAnimation();
                }
            }
        }
    }


    public void applyFromShortcutInfo(ShortcutInfo shortcutInfo) {
        applyFromShortcutInfo(shortcutInfo, false, true);
    }

    public void applyFromShortcutInfo(ShortcutInfo shortcutInfo, boolean z) {
        applyFromShortcutInfo(shortcutInfo, z, true);
    }

    public void applyFromShortcutInfo(ShortcutInfo shortcutInfo, boolean z, boolean badged) {
        Bitmap iconBitmap = badged ?
                shortcutInfo.getIcon(mLauncher.getIconCache()) :
                shortcutInfo.getUnbadgedIcon(mLauncher.getIconCache());
        if (iconBitmap == null) {
            iconBitmap = mLauncher.getIconCache().getDefaultIcon(Utilities.myUserHandle());
        }
        applyIconAndLabel(iconBitmap, shortcutInfo);
        setTag(shortcutInfo);
        if (shortcutInfo.itemType == LauncherSettings.Favorites.ITEM_TYPE_APPLICATION)
            applyClockIcon(shortcutInfo.getTargetComponent());
        if (z || shortcutInfo.isPromise()) {
            applyPromiseState(z);
        }
        applyBadgeState(shortcutInfo, false);
    }

    private void applyClockIcon(ComponentName componentName) {
        if (Utilities.isAnimatedClock(getContext(), componentName)) {
            setIcon(ClockIconDrawable.Companion.createWrapped(getContext()));
        }
    }

    public void applyFromApplicationInfo(AppInfo info) {
        applyFromApplicationInfo(info, true);
    }

    public void applyFromApplicationInfo(AppInfo info, boolean enableStates) {
        applyIconAndLabel(info.iconBitmap, info, enableStates);
        applyClockIcon(info.getTargetComponent());

        // We don't need to check the info since it's not a ShortcutInfo
        super.setTag(info);

        // Verify high res immediately
        verifyHighRes();
        applyBadgeState(info, false);
    }

    public void applyFromPackageItemInfo(PackageItemInfo info) {
        applyIconAndLabel(info.iconBitmap, info);
        // We don't need to check the info since it's not a ShortcutInfo
        super.setTag(info);

        // Verify high res immediately
        verifyHighRes();
    }

    private void applyIconAndLabel(Bitmap icon, ItemInfo info) {
        applyIconAndLabel(icon, info, true);
    }

    private void applyIconAndLabel(Bitmap icon, ItemInfo info, boolean enableStates) {
        FastBitmapDrawable iconDrawable = mLauncher.createIconDrawable(icon);
        iconDrawable.setIsDisabled(info.isDisabled());
        setIcon(iconDrawable);
        if (!mHideText)
            setText(info.title);
        if (info.contentDescription != null) {
            setContentDescription(info.isDisabled()
                    ? getContext().getString(R.string.disabled_app_label, info.contentDescription)
                    : info.contentDescription);
        }
    }

    /**
     * Overrides the default long press timeout.
     */
    public void setLongPressTimeout(int longPressTimeout) {
        mLongPressHelper.setLongPressTimeout(longPressTimeout);
    }

    @Override
    protected boolean setFrame(int left, int top, int right, int bottom) {
        if (getLeft() != left || getRight() != right || getTop() != top || getBottom() != bottom) {
            mBackgroundSizeChanged = true;
        }
        return super.setFrame(left, top, right, bottom);
    }

    @Override
    protected boolean verifyDrawable(@NonNull Drawable who) {
        return who == mBackground || super.verifyDrawable(who);
    }

    @Override
    public void setTag(Object tag) {
        if (tag != null) {
            LauncherModel.checkItemInfo((ItemInfo) tag);
        }
        super.setTag(tag);
    }

    /**
     * Returns the icon for this view.
     */
    public Drawable getIcon() {
        return mIcon;
    }

    /**
     * Returns whether the layout is horizontal.
     */
    public boolean isLayoutHorizontal() {
        return mLayoutHorizontal;
    }

    @Override
    public void setOnLongClickListener(OnLongClickListener l) {
        super.setOnLongClickListener(l);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // Call the superclass onTouchEvent first, because sometimes it changes the state to
        // isPressed() on an ACTION_UP
        boolean result = super.onTouchEvent(event);

        // Check for a stylus button press, if it occurs cancel any long press checks.
        if (mStylusEventHelper.onMotionEvent(event)) {
            mLongPressHelper.cancelLongPress();
            result = true;
        }

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                // So that the pressed outline is visible immediately on setStayPressed(),
                // we pre-create it on ACTION_DOWN (it takes a small but perceptible amount of time
                // to create it)
                if (!mDeferShadowGenerationOnTouch && mPressedBackground == null) {
                    mPressedBackground = mOutlineHelper.createMediumDropShadow(this);
                }

                // If we're in a stylus button press, don't check for long press.
                if (!mStylusEventHelper.inStylusButtonPressed()) {
                    mLongPressHelper.postCheckForLongPress();
                }
                break;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                // If we've touched down and up on an item, and it's still not "pressed", then
                // destroy the pressed outline
                if (!isPressed()) {
                    mPressedBackground = null;
                }

                mLongPressHelper.cancelLongPress();
                break;
            case MotionEvent.ACTION_MOVE:
                if (!Utilities.pointInView(this, event.getX(), event.getY(), mSlop)) {
                    mLongPressHelper.cancelLongPress();
                }
                break;
        }
        return result;
    }

    void setStayPressed(boolean stayPressed) {
        mStayPressed = stayPressed;
        if (!stayPressed) {
            HolographicOutlineHelper.obtain(getContext()).recycleShadowBitmap(mPressedBackground);
            mPressedBackground = null;
        } else {
            if (mPressedBackground == null) {
                mPressedBackground = mOutlineHelper.createMediumDropShadow(this);
            }
        }

        // Only show the shadow effect when persistent pressed state is set.
        ViewParent parent = getParent();
        if (parent != null && parent.getParent() instanceof BubbleTextShadowHandler) {
            ((BubbleTextShadowHandler) parent.getParent()).setPressedIcon(
                    this, mPressedBackground);
        }

        refreshDrawableState();
    }

    void clearPressedBackground() {
        setPressed(false);
        setStayPressed(false);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (super.onKeyDown(keyCode, event)) {
            // Pre-create shadow so show immediately on click.
            if (mPressedBackground == null) {
                mPressedBackground = mOutlineHelper.createMediumDropShadow(this);
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        // Unlike touch events, keypress event propagate pressed state change immediately,
        // without waiting for onClickHandler to execute. Disable pressed state changes here
        // to avoid flickering.
        mIgnorePressedStateChange = true;
        boolean result = super.onKeyUp(keyCode, event);

        mPressedBackground = null;
        mIgnorePressedStateChange = false;
        refreshDrawableState();
        return result;
    }

    @Override
    public void draw(Canvas canvas) {
        if (!mCustomShadowsEnabled || mShadowsDisabled) {
            if (mShadowsDisabled) {
                getPaint().clearShadowLayer();
            }
            super.draw(canvas);
            drawBadgeIfNecessary(canvas);
            return;
        }

        final Drawable background = mBackground;
        if (background != null) {
            final int scrollX = getScrollX();
            final int scrollY = getScrollY();

            if (mBackgroundSizeChanged) {
                background.setBounds(0, 0, getRight() - getLeft(), getBottom() - getTop());
                mBackgroundSizeChanged = false;
            }

            if ((scrollX | scrollY) == 0) {
                background.draw(canvas);
            } else {
                canvas.translate(scrollX, scrollY);
                background.draw(canvas);
                canvas.translate(-scrollX, -scrollY);
            }
        }

        // If text is transparent, don't draw any shadow
        if (getCurrentTextColor() == getResources().getColor(android.R.color.transparent)) {
            getPaint().clearShadowLayer();
            super.draw(canvas);
            drawBadgeIfNecessary(canvas);
            return;
        }

        // We enhance the shadow by drawing the shadow twice
        float density = getResources().getDisplayMetrics().density;
        getPaint().setShadowLayer(density * AMBIENT_SHADOW_RADIUS, 0, 0, AMBIENT_SHADOW_COLOR);
        super.draw(canvas);
        canvas.save(Canvas.CLIP_SAVE_FLAG);
        canvas.clipRect(getScrollX(), getScrollY() + getExtendedPaddingTop(),
                getScrollX() + getWidth(),
                getScrollY() + getHeight(), Region.Op.INTERSECT);
        getPaint().setShadowLayer(
                density * KEY_SHADOW_RADIUS, 0.0f, density * KEY_SHADOW_OFFSET, KEY_SHADOW_COLOR);
        super.draw(canvas);
        canvas.restore();
        drawBadgeIfNecessary(canvas);
    }

    /**
     * Draws the icon badge in the top right corner of the icon bounds.
     * @param canvas The canvas to draw to.
     */
    protected void drawBadgeIfNecessary(Canvas canvas) {
        if (!mForceHideBadge && (hasBadge() || mBadgeScale > 0)) {
            getIconBounds(mTempIconBounds);
            mTempSpaceForBadgeOffset.set((getWidth() - mIconSize) / 2, getPaddingTop());
            final int scrollX = getScrollX();
            final int scrollY = getScrollY();
            canvas.translate(scrollX, scrollY);
            mBadgeRenderer.draw(canvas, mBadgePalette, mBadgeInfo, mTempIconBounds, mBadgeScale,
                    mTempSpaceForBadgeOffset);
            canvas.translate(-scrollX, -scrollY);
        }
    }

    public void forceHideBadge(boolean z) {
        if (mForceHideBadge != z) {
            mForceHideBadge = z;
            if (z) {
                invalidate();
            } else if (hasBadge()) {
                ObjectAnimator.ofFloat(this, BADGE_SCALE_PROPERTY, new float[]{0.0f, 1.0f}).start();
            }
        }
    }

    private boolean hasBadge() {
        return mBadgeInfo != null;
    }


    public void getIconBounds(Rect rect) {
        int paddingTop = getPaddingTop();
        int width = (getWidth() - mIconSize) / 2;
        rect.set(width, paddingTop, mIconSize + width, mIconSize + paddingTop);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        if (mBackground != null) mBackground.setCallback(this);

        if (mIcon instanceof PreloadIconDrawable) {
            ((PreloadIconDrawable) mIcon).applyPreloaderTheme(getPreloaderTheme());
        }
        mSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (mCenterVertically) {
            Paint.FontMetrics fm = getPaint().getFontMetrics();
            int cellHeightPx = mIconSize + getCompoundDrawablePadding() +
                    (int) Math.ceil(fm.bottom - fm.top);
            int height = MeasureSpec.getSize(heightMeasureSpec);
            setPadding(getPaddingLeft(), (height - cellHeightPx) / 2, getPaddingRight(),
                    getPaddingBottom());
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mBackground != null) mBackground.setCallback(null);
    }

    @Override
    public void setTextColor(int color) {
        mTextColor = color;
        super.setTextColor(color);
    }

    @Override
    public void setTextColor(ColorStateList colors) {
        mTextColor = colors.getDefaultColor();
        super.setTextColor(colors);
    }

    public void setTextVisibility(boolean visible) {
        Resources res = getResources();
        if (visible) {
            super.setTextColor(mTextColor);
        } else {
            super.setTextColor(res.getColor(android.R.color.transparent));
        }
    }

    public boolean shouldTextBeVisible() {
        // Text should be visible everywhere but the hotseat.
        Object tag = getParent() instanceof FolderIcon ? ((View) getParent()).getTag() : getTag();
        ItemInfo info = tag instanceof ItemInfo ? (ItemInfo) tag : null;
        return info == null || info.container != LauncherSettings.Favorites.CONTAINER_HOTSEAT;
    }

    private void setTextAlpha(int alpha) {
        super.setTextColor(ColorUtils.setAlphaComponent(mTextColor, alpha));
    }

    private int getTextAlpha() {
        return Color.alpha(getCurrentTextColor());
    }

    /**
     * Creates an animator to fade the text in or out.
     * @param fadeIn Whether the text should fade in or fade out.
     */
    public ObjectAnimator createTextAlphaAnimator(boolean fadeIn) {
        int toAlpha = shouldTextBeVisible() && fadeIn ? Color.alpha(mTextColor) : 0;
        return ObjectAnimator.ofInt(this, TEXT_ALPHA_PROPERTY, toAlpha);
    }

    @Override
    public void cancelLongPress() {
        super.cancelLongPress();

        mLongPressHelper.cancelLongPress();
    }

    public void applyState(boolean promiseStateChanged) {
        if (getTag() instanceof ShortcutInfo) {
            ShortcutInfo info = (ShortcutInfo) getTag();
            final boolean isPromise = info.isPromise();
            final int progressLevel = isPromise ?
                    ((info.hasStatusFlag(ShortcutInfo.FLAG_INSTALL_SESSION_ACTIVE) ?
                            info.getInstallProgress() : 0)) : 100;

            setContentDescription(progressLevel > 0 ?
                    getContext().getString(R.string.app_downloading_title, info.title,
                            NumberFormat.getPercentInstance().format(progressLevel * 0.01)) :
                    getContext().getString(R.string.app_waiting_download_title, info.title));

            if (mIcon != null) {
                final PreloadIconDrawable preloadDrawable;
                if (mIcon instanceof PreloadIconDrawable) {
                    preloadDrawable = (PreloadIconDrawable) mIcon;
                } else {
                    preloadDrawable = new PreloadIconDrawable(mIcon, getPreloaderTheme());
                    setIcon(preloadDrawable);
                }

                preloadDrawable.setLevel(progressLevel);
                if (promiseStateChanged) {
                    preloadDrawable.maybePerformFinishedAnimation();
                }
            }
        }
    }


    public void applyBadgeState(ItemInfo itemInfo, boolean animate) {
        if (mIcon instanceof FastBitmapDrawable) {
            boolean wasBadged = mBadgeInfo != null;
            mBadgeInfo = mLauncher.getPopupDataProvider().getBadgeInfoForItem(itemInfo);
            boolean isBadged = mBadgeInfo != null;
            float newBadgeScale = isBadged ? 1f : 0;
            mBadgeRenderer = mLauncher.getDeviceProfile().mBadgeRenderer;
            if (wasBadged || isBadged) {
                mBadgePalette = IconPalette.getBadgePalette(getResources());
                if (mBadgePalette == null) {
                    mBadgePalette = ((FastBitmapDrawable) mIcon).getIconPalette();
                }
                // Animate when a badge is first added or when it is removed.
                if (animate && (wasBadged ^ isBadged) && isShown()) {
                    ObjectAnimator.ofFloat(this, BADGE_SCALE_PROPERTY, newBadgeScale).start();
                } else {
                    mBadgeScale = newBadgeScale;
                    invalidate();
                }
            }
        }
    }

    public IconPalette getBadgePalette() {
        return mBadgePalette;
    }


    private Theme getPreloaderTheme() {
        Object tag = getTag();
        int style = ((tag != null) && (tag instanceof ShortcutInfo) &&
                (((ShortcutInfo) tag).container >= 0)) ? R.style.PreloadIcon_Folder
                : R.style.PreloadIcon;
        Theme theme = sPreloaderThemes.get(style);
        if (theme == null) {
            theme = getResources().newTheme();
            theme.applyStyle(style, true);
            sPreloaderThemes.put(style, theme);
        }
        return theme;
    }

    /**
     * Sets the icon for this view based on the layout direction.
     */
    private void setIcon(Drawable icon) {
        mIcon = icon;
        mIcon.setBounds(0, 0, mIconSize, mIconSize);
        if (mIsIconVisible) {
            applyCompoundDrawables(mIcon);
        }
    }

    public void setIconVisible(boolean visible) {
        mIsIconVisible = visible;
        mDisableRelayout = true;
        Drawable icon = mIcon;
        if (!visible) {
            icon = new ColorDrawable(Color.TRANSPARENT);
            icon.setBounds(0, 0, mIconSize, mIconSize);
        }
        applyCompoundDrawables(icon);
        mDisableRelayout = false;
    }

    protected void applyCompoundDrawables(Drawable icon) {
        if (mLayoutHorizontal) {
            setCompoundDrawablesRelative(icon, null, null, null);
        } else {
            setCompoundDrawables(null, icon, null, null);
        }
    }

    @Override
    public void requestLayout() {
        if (!mDisableRelayout) {
            super.requestLayout();
        }
    }

    /**
     * Applies the item info if it is same as what the view is pointing to currently.
     */
    public void reapplyItemInfo(ItemInfoWithIcon info) {
        if (getTag() == info) {
            mIconLoadRequest = null;
            mDisableRelayout = true;

            // Optimization: Starting in N, pre-uploads the bitmap to RenderThread.
            //info.iconBitmap.prepareToDraw();

            if (info instanceof AppInfo) {
                applyFromApplicationInfo((AppInfo) info);
            } else if (info instanceof ShortcutInfo) {
                applyFromShortcutInfo((ShortcutInfo) info);
                if ((info.rank < FolderIcon.NUM_ITEMS_IN_PREVIEW) && (info.container >= 0)) {
                    View folderIcon =
                            mLauncher.getWorkspace().getHomescreenIconByItemId(info.container);
                    if (folderIcon != null) {
                        folderIcon.invalidate();
                    }
                }
            } else if (info instanceof PackageItemInfo) {
                applyFromPackageItemInfo((PackageItemInfo) info);
            }

            mDisableRelayout = false;
        }
    }

    /**
     * Verifies that the current icon is high-res otherwise posts a request to load the icon.
     */
    public void verifyHighRes() {
        if (mIconLoadRequest != null) {
            mIconLoadRequest.cancel();
            mIconLoadRequest = null;
        }
        if (getTag() instanceof AppInfo) {
            AppInfo info = (AppInfo) getTag();
            if (info.usingLowResIcon) {
                mIconLoadRequest = LauncherAppState.getInstance().getIconCache()
                        .updateIconInBackground(BubbleTextView.this, info);
            }
        } else if (getTag() instanceof ShortcutInfo) {
            ShortcutInfo info = (ShortcutInfo) getTag();
            if (info.usingLowResIcon) {
                mIconLoadRequest = LauncherAppState.getInstance().getIconCache()
                        .updateIconInBackground(BubbleTextView.this, info);
            }
        } else if (getTag() instanceof PackageItemInfo) {
            PackageItemInfo info = (PackageItemInfo) getTag();
            if (info.usingLowResIcon) {
                mIconLoadRequest = LauncherAppState.getInstance().getIconCache()
                        .updateIconInBackground(BubbleTextView.this, info);
            }
        }
    }

    /**
     * Interface to be implemented by the grand parent to allow click shadow effect.
     */
    public interface BubbleTextShadowHandler {
        void setPressedIcon(BubbleTextView icon, Bitmap background);
    }
}
