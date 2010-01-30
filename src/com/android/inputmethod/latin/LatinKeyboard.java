/*
 * Copyright (C) 2008-2009 Google Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.android.inputmethod.latin;

import java.util.List;
import java.util.Locale;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.Paint.Align;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.inputmethodservice.Keyboard;
import android.text.TextPaint;
import android.view.ViewConfiguration;
import android.view.inputmethod.EditorInfo;

public class LatinKeyboard extends Keyboard {

    private Drawable mShiftLockIcon;
    private Drawable mShiftLockPreviewIcon;
    private Drawable mOldShiftIcon;
    private Drawable mOldShiftPreviewIcon;
    private Drawable mSpaceIcon;
    private Drawable mSpacePreviewIcon;
    private Drawable mMicIcon;
    private Drawable mMicPreviewIcon;
    private Drawable m123MicIcon;
    private Drawable m123MicPreviewIcon;
    private Drawable mButtonArrowLeftIcon;
    private Drawable mButtonArrowRightIcon;
    private Key mShiftKey;
    private Key mEnterKey;
    private Key mF1Key;
    private Key mSpaceKey;
    private int mSpaceKeyIndex = -1;
    private int mSpaceDragStartX;
    private int mSpaceDragLastDiff;
    /* package */ Locale mLocale;
    private LanguageSwitcher mLanguageSwitcher;
    private Resources mRes;
    private Context mContext;
    private int mMode;
    private boolean mHasVoice;
    private boolean mCurrentlyInSpace;
    private SlidingLocaleDrawable mSlidingLocaleIcon;
    private Rect mBounds = new Rect();

    private int mExtensionResId; 
    
    private static final int SHIFT_OFF = 0;
    private static final int SHIFT_ON = 1;
    private static final int SHIFT_LOCKED = 2;
    
    private int mShiftState = SHIFT_OFF;

    private static final float SPACEBAR_DRAG_THRESHOLD = 0.8f;

    static int sSpacebarVerticalCorrection;

    public LatinKeyboard(Context context, int xmlLayoutResId) {
        this(context, xmlLayoutResId, 0, false);
    }

    public LatinKeyboard(Context context, int xmlLayoutResId, int mode, boolean hasVoice) {
        super(context, xmlLayoutResId, mode);
        final Resources res = context.getResources();
        mContext = context;
        mMode = mode;
        mRes = res;
        mHasVoice = hasVoice;
        mShiftLockIcon = res.getDrawable(R.drawable.sym_keyboard_shift_locked);
        mShiftLockPreviewIcon = res.getDrawable(R.drawable.sym_keyboard_feedback_shift_locked);
        mShiftLockPreviewIcon.setBounds(0, 0, 
                mShiftLockPreviewIcon.getIntrinsicWidth(),
                mShiftLockPreviewIcon.getIntrinsicHeight());
        mSpaceIcon = res.getDrawable(R.drawable.sym_keyboard_space);
        mSpacePreviewIcon = res.getDrawable(R.drawable.sym_keyboard_feedback_space);
        mMicIcon = res.getDrawable(R.drawable.sym_keyboard_mic);
        mMicPreviewIcon = res.getDrawable(R.drawable.sym_keyboard_feedback_mic);
        mButtonArrowLeftIcon = res.getDrawable(R.drawable.sym_keyboard_language_arrows_left);
        mButtonArrowRightIcon = res.getDrawable(R.drawable.sym_keyboard_language_arrows_right);
        sSpacebarVerticalCorrection = res.getDimensionPixelOffset(
                R.dimen.spacebar_vertical_correction);
        setF1Key();
        mSpaceKeyIndex = indexOf((int) ' ');
    }

    public LatinKeyboard(Context context, int layoutTemplateResId, 
            CharSequence characters, int columns, int horizontalPadding) {
        super(context, layoutTemplateResId, characters, columns, horizontalPadding);
    }

    @Override
    protected Key createKeyFromXml(Resources res, Row parent, int x, int y, 
            XmlResourceParser parser) {
        Key key = new LatinKey(res, parent, x, y, parser);
        switch (key.codes[0]) {
        case 10:
            mEnterKey = key;
            break;
        case LatinKeyboardView.KEYCODE_F1:
            mF1Key = key;
            break;
        case 32:
            mSpaceKey = key;
            break;
        }
        return key;
    }

    void setImeOptions(Resources res, int mode, int options) {
        if (mEnterKey != null) {
            // Reset some of the rarely used attributes.
            mEnterKey.popupCharacters = null;
            mEnterKey.popupResId = 0;
            mEnterKey.text = null;
            switch (options&(EditorInfo.IME_MASK_ACTION|EditorInfo.IME_FLAG_NO_ENTER_ACTION)) {
                case EditorInfo.IME_ACTION_GO:
                    mEnterKey.iconPreview = null;
                    mEnterKey.icon = null;
                    mEnterKey.label = res.getText(R.string.label_go_key);
                    break;
                case EditorInfo.IME_ACTION_NEXT:
                    mEnterKey.iconPreview = null;
                    mEnterKey.icon = null;
                    mEnterKey.label = res.getText(R.string.label_next_key);
                    break;
                case EditorInfo.IME_ACTION_DONE:
                    mEnterKey.iconPreview = null;
                    mEnterKey.icon = null;
                    mEnterKey.label = res.getText(R.string.label_done_key);
                    break;
                case EditorInfo.IME_ACTION_SEARCH:
                    mEnterKey.iconPreview = res.getDrawable(
                            R.drawable.sym_keyboard_feedback_search);
                    mEnterKey.icon = res.getDrawable(
                            R.drawable.sym_keyboard_search);
                    mEnterKey.label = null;
                    break;
                case EditorInfo.IME_ACTION_SEND:
                    mEnterKey.iconPreview = null;
                    mEnterKey.icon = null;
                    mEnterKey.label = res.getText(R.string.label_send_key);
                    break;
                default:
                    if (mode == KeyboardSwitcher.MODE_IM) {
                        mEnterKey.icon = null;
                        mEnterKey.iconPreview = null;
                        mEnterKey.label = ":-)";
                        mEnterKey.text = ":-) ";
                        mEnterKey.popupResId = R.xml.popup_smileys;
                    } else {
                        mEnterKey.iconPreview = res.getDrawable(
                                R.drawable.sym_keyboard_feedback_return);
                        mEnterKey.icon = res.getDrawable(
                                R.drawable.sym_keyboard_return);
                        mEnterKey.label = null;
                    }
                    break;
            }
            // Set the initial size of the preview icon
            if (mEnterKey.iconPreview != null) {
                mEnterKey.iconPreview.setBounds(0, 0, 
                        mEnterKey.iconPreview.getIntrinsicWidth(),
                        mEnterKey.iconPreview.getIntrinsicHeight());
            }
        }
    }
    
    void enableShiftLock() {
        int index = getShiftKeyIndex();
        if (index >= 0) {
            mShiftKey = getKeys().get(index);
            if (mShiftKey instanceof LatinKey) {
                ((LatinKey)mShiftKey).enableShiftLock();
            }
            mOldShiftIcon = mShiftKey.icon;
            mOldShiftPreviewIcon = mShiftKey.iconPreview;
        }
    }

    void setShiftLocked(boolean shiftLocked) {
        if (mShiftKey != null) {
            if (shiftLocked) {
                mShiftKey.on = true;
                mShiftKey.icon = mShiftLockIcon;
                mShiftState = SHIFT_LOCKED;
            } else {
                mShiftKey.on = false;
                mShiftKey.icon = mShiftLockIcon;
                mShiftState = SHIFT_ON;
            }
        }
    }

    boolean isShiftLocked() {
        return mShiftState == SHIFT_LOCKED;
    }
    
    @Override
    public boolean setShifted(boolean shiftState) {
        boolean shiftChanged = false;
        if (mShiftKey != null) {
            if (shiftState == false) {
                shiftChanged = mShiftState != SHIFT_OFF;
                mShiftState = SHIFT_OFF;
                mShiftKey.on = false;
                mShiftKey.icon = mOldShiftIcon;
            } else {
                if (mShiftState == SHIFT_OFF) {
                    shiftChanged = mShiftState == SHIFT_OFF;
                    mShiftState = SHIFT_ON;
                    mShiftKey.icon = mShiftLockIcon;
                }
            }
        } else {
            return super.setShifted(shiftState);
        }
        return shiftChanged;
    }

    @Override
    public boolean isShifted() {
        if (mShiftKey != null) {
            return mShiftState != SHIFT_OFF;
        } else {
            return super.isShifted();
        }
    }

    public void setExtension(int resId) {
        mExtensionResId = resId;
    }

    public int getExtension() {
        return mExtensionResId;
    }

    private void setF1Key() {
        if (mF1Key == null) return;
        if (!mHasVoice) {
            mF1Key.label = ",";
            mF1Key.codes = new int[] { ',' };
            mF1Key.icon = null;
            mF1Key.iconPreview = null;
        } else {
            mF1Key.codes = new int[] { LatinKeyboardView.KEYCODE_VOICE };
            mF1Key.label = null;
            mF1Key.icon = mMicIcon;
            mF1Key.iconPreview = mMicPreviewIcon;
        }
    }

    private void updateSpaceBarForLocale() {
        if (mLocale != null) {
            // Create the graphic for spacebar
            Bitmap buffer = Bitmap.createBitmap(mSpaceKey.width, mSpaceIcon.getIntrinsicHeight(),
                    Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(buffer);
            canvas.drawColor(0x00000000, PorterDuff.Mode.CLEAR);
            Paint paint = new Paint();
            paint.setAntiAlias(true);
            // Get the text size from the theme
            paint.setTextSize(getTextSizeFromTheme(android.R.style.TextAppearance_Small, 14));
            paint.setTextAlign(Align.CENTER);
            // Draw a drop shadow for the text
            paint.setShadowLayer(2f, 0, 0, 0xFF000000);
            paint.setColor(0xFF808080);
            final String language = getInputLanguage(mSpaceKey.width, paint);
            final int ascent = (int) -paint.ascent();
            canvas.drawText(language,
                    buffer.getWidth() / 2, ascent, paint);
            // Put arrows on either side of the text
            if (mLanguageSwitcher.getLocaleCount() > 1) {
                Rect bounds = new Rect();
                paint.getTextBounds(language, 0, language.length(), bounds);
                drawButtonArrow(mButtonArrowLeftIcon, canvas,
                        (mSpaceKey.width - bounds.right) / 2
                        - mButtonArrowLeftIcon.getIntrinsicWidth(),
                        (int) paint.getTextSize());
                drawButtonArrow(mButtonArrowRightIcon, canvas,
                        (mSpaceKey.width + bounds.right) / 2, (int) paint.getTextSize());
            }
            // Draw the spacebar icon at the bottom
            int x = (buffer.getWidth() - mSpaceIcon.getIntrinsicWidth()) / 2;
            int y = buffer.getHeight() - mSpaceIcon.getIntrinsicHeight();
            mSpaceIcon.setBounds(x, y, 
                    x + mSpaceIcon.getIntrinsicWidth(), y + mSpaceIcon.getIntrinsicHeight());
            mSpaceIcon.draw(canvas);
            mSpaceKey.icon = new BitmapDrawable(mRes, buffer);
            mSpaceKey.repeatable = mLanguageSwitcher.getLocaleCount() < 2;
        } else {
            mSpaceKey.icon = mRes.getDrawable(R.drawable.sym_keyboard_space);
            mSpaceKey.repeatable = true;
        }
    }

    private void drawButtonArrow(Drawable arrow, Canvas canvas, int x, int bottomY) {
        arrow.setBounds(x, bottomY - arrow.getIntrinsicHeight(), x + arrow.getIntrinsicWidth(),
                bottomY);
        arrow.draw(canvas);
    }

    private String getInputLanguage(int widthAvail, Paint paint) {
        return chooseDisplayName(mLanguageSwitcher.getInputLocale(), widthAvail, paint);
    }

    private String getNextInputLanguage(int widthAvail, Paint paint) {
        return chooseDisplayName(mLanguageSwitcher.getNextInputLocale(), widthAvail, paint);
    }

    private String getPrevInputLanguage(int widthAvail, Paint paint) {
        return chooseDisplayName(mLanguageSwitcher.getPrevInputLocale(), widthAvail, paint);
    }

    private String chooseDisplayName(Locale locale, int widthAvail, Paint paint) {
        if (widthAvail < (int) (.35 * getMinWidth())) {
            return locale.getLanguage().substring(0, 2).toUpperCase(locale);
        } else {
            return locale.getDisplayLanguage(locale);
        }
    }

    private void updateLocaleDrag(int diff) {
        if (mSlidingLocaleIcon == null) {
            mSlidingLocaleIcon = new SlidingLocaleDrawable(mSpacePreviewIcon, mSpaceKey.width,
                    mSpacePreviewIcon.getIntrinsicHeight());
            mSlidingLocaleIcon.setBounds(0, 0, mSpaceKey.width,
                    mSpacePreviewIcon.getIntrinsicHeight());
            mSpaceKey.iconPreview = mSlidingLocaleIcon;
        }
        mSlidingLocaleIcon.setDiff(diff);
        if (Math.abs(diff) == Integer.MAX_VALUE) {
            mSpaceKey.iconPreview = mSpacePreviewIcon;
        } else {
            mSpaceKey.iconPreview = mSlidingLocaleIcon;
        }
        mSpaceKey.iconPreview.invalidateSelf();
    }

    public int getLanguageChangeDirection() {
        if (mSpaceKey == null || mLanguageSwitcher.getLocaleCount() < 2
                || Math.abs(mSpaceDragLastDiff) < mSpaceKey.width * SPACEBAR_DRAG_THRESHOLD ) {
            return 0; // No change
        }
        return mSpaceDragLastDiff > 0 ? 1 : -1;
    }

    public void setLanguageSwitcher(LanguageSwitcher switcher) {
        mLanguageSwitcher = switcher;
        Locale locale = mLanguageSwitcher.getLocaleCount() > 0
                ? mLanguageSwitcher.getInputLocale()
                : null;
        if (mLocale != null && mLocale.equals(locale)) return;
        mLocale = locale;
        updateSpaceBarForLocale();
    }

    boolean isCurrentlyInSpace() {
        return mCurrentlyInSpace;
    }

    void keyReleased() {
        mCurrentlyInSpace = false;
        mSpaceDragLastDiff = 0;
        if (mSpaceKey != null) {
            updateLocaleDrag(Integer.MAX_VALUE);
        }
    }

    /**
     * Does the magic of locking the touch gesture into the spacebar when
     * switching input languages.
     */
    boolean isInside(LatinKey key, int x, int y) {
        final int code = key.codes[0];
        if (code == KEYCODE_SHIFT ||
                code == KEYCODE_DELETE) {
            y -= key.height / 10;
            if (code == KEYCODE_SHIFT) x += key.width / 6;
            if (code == KEYCODE_DELETE) x -= key.width / 6;
        } else if (code == LatinIME.KEYCODE_SPACE) {
            y += LatinKeyboard.sSpacebarVerticalCorrection;
            if (mLanguageSwitcher.getLocaleCount() > 1) {
                if (mCurrentlyInSpace) {
                    int diff = x - mSpaceDragStartX;
                    if (Math.abs(diff - mSpaceDragLastDiff) > 0) {
                        updateLocaleDrag(diff);
                    }
                    mSpaceDragLastDiff = diff;
                    return true;
                } else {
                    boolean insideSpace = key.isInsideSuper(x, y);
                    if (insideSpace) {
                        mCurrentlyInSpace = true;
                        mSpaceDragStartX = x;
                        updateLocaleDrag(0);
                    }
                    return insideSpace;
                }
            }
        }

        // Lock into the spacebar
        if (mCurrentlyInSpace) return false;

        return key.isInsideSuper(x, y);
    }

    @Override
    public int[] getNearestKeys(int x, int y) {
        if (mCurrentlyInSpace) {
            return new int[] { mSpaceKeyIndex };
        } else {
            return super.getNearestKeys(x, y);
        }
    }

    private int indexOf(int code) {
        List<Key> keys = getKeys();
        int count = keys.size();
        for (int i = 0; i < count; i++) {
            if (keys.get(i).codes[0] == code) return i;
        }
        return -1;
    }

    private int getTextSizeFromTheme(int style, int defValue) {
        TypedArray array = mContext.getTheme().obtainStyledAttributes(
                style, new int[] { android.R.attr.textSize });
        int textSize = array.getDimensionPixelSize(array.getResourceId(0, 0), defValue);
        return textSize;
    }

    class LatinKey extends Keyboard.Key {
        
        private boolean mShiftLockEnabled;
        
        public LatinKey(Resources res, Keyboard.Row parent, int x, int y, 
                XmlResourceParser parser) {
            super(res, parent, x, y, parser);
            if (popupCharacters != null && popupCharacters.length() == 0) {
                // If there is a keyboard with no keys specified in popupCharacters
                popupResId = 0;
            }
        }
        
        void enableShiftLock() {
            mShiftLockEnabled = true;
        }

        @Override
        public void onReleased(boolean inside) {
            if (!mShiftLockEnabled) {
                super.onReleased(inside);
            } else {
                pressed = !pressed;
            }
        }

        /**
         * Overriding this method so that we can reduce the target area for certain keys.
         */
        @Override
        public boolean isInside(int x, int y) {
            return LatinKeyboard.this.isInside(this, x, y);
        }

        boolean isInsideSuper(int x, int y) {
            return super.isInside(x, y);
        }
    }

    /**
     * Animation to be displayed on the spacebar preview popup when switching 
     * languages by swiping the spacebar. It draws the current, previous and
     * next languages and moves them by the delta of touch movement on the spacebar.
     */
    class SlidingLocaleDrawable extends Drawable {

        private int mWidth;
        private int mHeight;
        private Drawable mBackground;
        private int mDiff;
        private TextPaint mTextPaint;
        private int mMiddleX;
        private int mAscent;
        private Drawable mLeftDrawable;
        private Drawable mRightDrawable;
        private boolean mHitThreshold;
        private int     mThreshold;
        private String mCurrentLanguage;
        private String mNextLanguage;
        private String mPrevLanguage;

        public SlidingLocaleDrawable(Drawable background, int width, int height) {
            mBackground = background;
            mBackground.setBounds(0, 0,
                    mBackground.getIntrinsicWidth(), mBackground.getIntrinsicHeight());
            mWidth = width;
            mHeight = height;
            mTextPaint = new TextPaint();
            int textSize = getTextSizeFromTheme(android.R.style.TextAppearance_Medium, 18);
            mTextPaint.setTextSize(textSize);
            mTextPaint.setColor(0);
            mTextPaint.setTextAlign(Align.CENTER);
            mTextPaint.setAlpha(255);
            mTextPaint.setAntiAlias(true);
            mAscent = (int) mTextPaint.ascent();
            mMiddleX = (mWidth - mBackground.getIntrinsicWidth()) / 2;
            mLeftDrawable =
                    mRes.getDrawable(R.drawable.sym_keyboard_feedback_language_arrows_left);
            mRightDrawable =
                    mRes.getDrawable(R.drawable.sym_keyboard_feedback_language_arrows_right);
            mLeftDrawable.setBounds(0, 0,
                    mLeftDrawable.getIntrinsicWidth(), mLeftDrawable.getIntrinsicHeight());
            mRightDrawable.setBounds(mWidth - mRightDrawable.getIntrinsicWidth(), 0,
                    mWidth, mRightDrawable.getIntrinsicHeight());
            mThreshold = ViewConfiguration.get(mContext).getScaledTouchSlop();
        }

        void setDiff(int diff) {
            if (diff == Integer.MAX_VALUE) {
                mHitThreshold = false;
                mCurrentLanguage = null;
                return;
            }
            mDiff = diff;
            if (mDiff > mWidth) mDiff = mWidth;
            if (mDiff < -mWidth) mDiff = -mWidth;
            if (Math.abs(mDiff) > mThreshold) mHitThreshold = true;
            invalidateSelf();
        }

        @Override
        public void draw(Canvas canvas) {
            canvas.save();
            if (mHitThreshold) {
                mTextPaint.setColor(0);
                canvas.clipRect(0, 0, mWidth, mHeight);
                int alpha = (255 * Math.max(0, mWidth / 2 - Math.abs(mDiff))) / (mWidth / 2);
                mTextPaint.setAlpha(alpha);

                if (mCurrentLanguage == null) {
                    mCurrentLanguage = getInputLanguage(mWidth, mTextPaint);
                    mNextLanguage = getNextInputLanguage(mWidth, mTextPaint);
                    mPrevLanguage = getPrevInputLanguage(mWidth, mTextPaint);
                }

                canvas.drawText(mCurrentLanguage,
                        mWidth / 2 + mDiff, -mAscent + 4, mTextPaint);
                mTextPaint.setAlpha(255 - alpha);
                canvas.drawText(mNextLanguage,
                        mDiff - mWidth / 2, -mAscent + 4, mTextPaint);
                canvas.drawText(mPrevLanguage,
                        mDiff + mWidth + mWidth / 2, -mAscent + 4, mTextPaint);
                mLeftDrawable.draw(canvas);
                mRightDrawable.draw(canvas);
            }
            if (mBackground != null) {
                canvas.translate(mMiddleX, 0);
                mBackground.draw(canvas);
            }
            canvas.restore();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public void setAlpha(int alpha) {
            // Ignore
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            // Ignore
        }

        @Override
        public int getIntrinsicWidth() {
            return mWidth;
        }

        @Override
        public int getIntrinsicHeight() {
            return mHeight;
        }
    }
}
