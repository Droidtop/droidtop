/*
 * Copyright (C) 2008 The Android Open Source Project
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

package org.pocketworkstation.pckeyboard;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Paint.Align;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class CandidateView extends View {

    private static final int OUT_OF_BOUNDS_WORD_INDEX = -1;
    private static final int OUT_OF_BOUNDS_X_COORD = -1;

    private LatinIME mService;
    private final ArrayList<CharSequence> mSuggestions = new ArrayList<CharSequence>();
    private boolean mShowingCompletions;
    private CharSequence mSelectedString;
    private int mSelectedIndex;
    private int mTouchX = OUT_OF_BOUNDS_X_COORD;
    private final Drawable mSelectionHighlight;
    private boolean mTypedWordValid;
    
    private boolean mHaveMinimalSuggestion;
    
    private Rect mBgPadding;

    private final TextView mPreviewText;
    private final PopupWindow mPreviewPopup;
    private int mCurrentWordIndex;
    private Drawable mDivider;
    
    private static final int MAX_SUGGESTIONS = 32;
    private static final int SCROLL_PIXELS = 20;
    
    private final int[] mWordWidth = new int[MAX_SUGGESTIONS];
    private final int[] mWordX = new int[MAX_SUGGESTIONS];
    private int mPopupPreviewX;
    private int mPopupPreviewY;

    private static final int X_GAP = 10;
    
    private final int mColorNormal;
    private final int mColorRecommended;
    private final int mColorOther;
    private final Paint mPaint;
    private final int mDescent;
    private boolean mScrolled;
    private boolean mShowingAddToDictionary;
    private CharSequence mAddToDictionaryHint;

    private int mTargetScrollX;

    private final int mMinTouchableWidth;

    private int mTotalWidth;
    
    private final GestureDetector mGestureDetector;

    // droidtop patch (Droidtop/tracker#903 H12a): new suggestions fade and rise into the strip (KeyMotion).
    private long mArrivedAt;
    private boolean mArriving;

    // droidtop patch (Droidtop/tracker#903 H6): the one candidate bar for every engine (CandidateBar is its model).
    // Hacker's suggestions are drawn exactly as before; a language's own engine adds small annotations, its chosen
    // candidate highlighted, and an expand button at the row's end that opens every candidate in a grid over the
    // keys. The grid is this same view, drawn in grid mode in a popup. The pad's ring (PadKeys) is drawn on the
    // candidate or the button it has selected.
    private final CandidateBar mOwnBar = new CandidateBar();
    private CandidateBar mBar = mOwnBar;
    private boolean mShowingEngine;
    private final ArrayList<CharSequence> mAnnotations = new ArrayList<CharSequence>();
    private final Paint mAnnotationPaint;
    private final Paint mRingPaint;
    private final int mExpandWidth;
    private final int mRowHeight;
    private boolean mGridMode;
    private CandidateView mGrid;
    private PopupWindow mGridPopup;
    private boolean mGridPaged;
    // droidtop patch (Droidtop/tracker#903 H7a): an engine's composition mode on a button at the row's left end (its
    // width this frame, 0 with none), and a notice line in place of candidates while the engine gets ready.
    private int mModeWidth;
    private static final String EXPAND_GLYPH = "\u25BE";
    private static final String COLLAPSE_GLYPH = "\u25B4";

    /**
     * Construct a CandidateView for showing suggested words for completion.
     * @param context
     * @param attrs
     */
    public CandidateView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mSelectionHighlight = context.getResources().getDrawable(
                R.drawable.list_selector_background_pressed);

        LayoutInflater inflate =
            (LayoutInflater) context
                    .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        Resources res = context.getResources();
        mPreviewPopup = new PopupWindow(context);
        mPreviewText = (TextView) inflate.inflate(R.layout.candidate_preview, null);
        mPreviewPopup.setWindowLayoutMode(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        mPreviewPopup.setContentView(mPreviewText);
        mPreviewPopup.setBackgroundDrawable(null);
        mPreviewPopup.setAnimationStyle(R.style.KeyPreviewAnimation);
        // Enable clipping for Android P, keep disabled for older versions.
        boolean clippingEnabled = (Build.VERSION.SDK_INT >= 28 /* Build.VERSION_CODES.P */);
        mPreviewPopup.setClippingEnabled(clippingEnabled);
        mColorNormal = res.getColor(R.color.candidate_normal);
        mColorRecommended = res.getColor(R.color.candidate_recommended);
        mColorOther = res.getColor(R.color.candidate_other);
        mDivider = res.getDrawable(R.drawable.keyboard_suggest_strip_divider);
        mAddToDictionaryHint = res.getString(R.string.hint_add_to_dictionary);

        mPaint = new Paint();
        mPaint.setColor(mColorNormal);
        mPaint.setAntiAlias(true);
        mPaint.setTextSize(mPreviewText.getTextSize() * LatinIME.sKeyboardSettings.candidateScalePref);
        mPaint.setStrokeWidth(0);
        mPaint.setTextAlign(Align.CENTER);
        mDescent = (int) mPaint.descent();
        mMinTouchableWidth = (int)res.getDimension(R.dimen.candidate_min_touchable_width);
        mAnnotationPaint = new Paint(mPaint);
        mAnnotationPaint.setTextSize(mPaint.getTextSize() * 0.6f);
        mAnnotationPaint.setTextAlign(Align.CENTER);
        mAnnotationPaint.setColor(mColorOther);
        final float density = res.getDisplayMetrics().density;
        mRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mRingPaint.setStyle(Paint.Style.STROKE);
        mRingPaint.setStrokeWidth(3f * density);
        mExpandWidth = (int) (44 * density);
        mRowHeight = (int) res.getDimension(R.dimen.candidate_strip_height);
        
        mGestureDetector = new GestureDetector(
                new CandidateStripGestureListener(mMinTouchableWidth));
        setWillNotDraw(false);
        setHorizontalScrollBarEnabled(false);
        setVerticalScrollBarEnabled(false);
        scrollTo(0, getScrollY());
    }

    private class CandidateStripGestureListener extends GestureDetector.SimpleOnGestureListener {
        private final int mTouchSlopSquare;

        public CandidateStripGestureListener(int touchSlop) {
            // Slightly reluctant to scroll to be able to easily choose the suggestion
            mTouchSlopSquare = touchSlop * touchSlop;
        }

        @Override
        public void onLongPress(MotionEvent me) {
            if (mSuggestions.size() > 0 && me.getX() >= mModeWidth) {
                if (me.getX() + getScrollX() < mModeWidth + mWordWidth[0] && getScrollX() < 10) {
                    longPressFirstWord();
                }
            }
        }

        @Override
        public boolean onDown(MotionEvent e) {
            mScrolled = false;
            return false;
        }

        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2,
                float distanceX, float distanceY) {
            if (mGridMode) return onGridScroll(e1, e2);
            if (!mScrolled) {
                // This is applied only when we recognize that scrolling is starting.
                final int deltaX = (int) (e2.getX() - e1.getX());
                final int deltaY = (int) (e2.getY() - e1.getY());
                final int distance = (deltaX * deltaX) + (deltaY * deltaY);
                if (distance < mTouchSlopSquare) {
                    return true;
                }
                mScrolled = true;
            }

            final int width = getWidth();
            mScrolled = true;
            int scrollX = getScrollX();
            scrollX += (int) distanceX;
            if (scrollX < 0) {
                scrollX = 0;
            }
            if (distanceX > 0 && scrollX + width > mTotalWidth) {
                scrollX -= (int) distanceX;
            }
            mTargetScrollX = scrollX;
            scrollTo(scrollX, getScrollY());
            hidePreview();
            invalidate();
            return true;
        }
    }

    /**
     * A connection back to the service to communicate with the text field
     * @param listener
     */
    public void setService(LatinIME listener) {
        mService = listener;
    }
    
    @Override
    public int computeHorizontalScrollRange() {
        return mTotalWidth;
    }

    /**
     * If the canvas is null, then only touch calculations are performed to pick the target
     * candidate.
     */
    @Override
    protected void onDraw(Canvas canvas) {
        if (canvas != null) {
            super.onDraw(canvas);
        }
        if (mGridMode) {
            if (canvas != null) drawGrid(canvas);
            return;
        }
        mTotalWidth = 0;
        
        final int height = getHeight();
        if (mBgPadding == null) {
            mBgPadding = new Rect(0, 0, 0, 0);
            if (getBackground() != null) {
                getBackground().getPadding(mBgPadding);
            }
            mDivider.setBounds(0, 0, mDivider.getIntrinsicWidth(),
                    mDivider.getIntrinsicHeight());
        }

        final int count = mSuggestions.size();
        final Rect bgPadding = mBgPadding;
        final Paint paint = mPaint;
        final int touchX = mTouchX;
        final int scrollX = getScrollX();
        final boolean scrolled = mScrolled;
        final boolean typedWordValid = mTypedWordValid;
        final int y = (int) (height + mPaint.getTextSize() - mDescent) / 2;
        // New suggestions: their opacity and how far below the line they still are, for this frame (#903 H12a).
        final float motion = KeyboardMotion.getScale();
        final long arrivedFor = android.view.animation.AnimationUtils.currentAnimationTimeMillis() - mArrivedAt;
        final float arriveAlpha = mArriving ? KeyMotion.candidateAlpha(arrivedFor, motion) : 1f;
        final float arriveDrop = mArriving
                ? KeyMotion.candidateRiseDp(arrivedFor, motion) * getResources().getDisplayMetrics().density : 0f;

        boolean existsAutoCompletion = false;
        // The expand button (#903 H6) sits over the row's right end; a touch there is the button's, not a word's.
        final boolean expandable = mShowingEngine && mBar.getExpandable();
        // The mode button (#903 H7a) sits over the row's left end in the same way; the words start after it.
        final CharSequence modeLabel = mShowingEngine ? mBar.getModeLabel() : null;
        mModeWidth = modeLabel == null ? 0
                : Math.max(mExpandWidth, (int) mPaint.measureText(modeLabel, 0, modeLabel.length()) + X_GAP * 2);
        final boolean touchOnButton = (expandable && touchX >= getWidth() - mExpandWidth)
                || (touchX != OUT_OF_BOUNDS_X_COORD && touchX < mModeWidth);
        final boolean ring = mBar.getPadActive();
        if (ring) mRingPaint.setColor(KeyboardAppearance.colors(getContext()).getAccent());

        int x = mModeWidth;
        for (int i = 0; i < count; i++) {
            CharSequence suggestion = mSuggestions.get(i);
            if (suggestion == null) continue;
            final int wordLength = suggestion.length();
            final CharSequence annotation = i < mAnnotations.size() ? mAnnotations.get(i) : null;
            final int annotationWidth = annotation == null ? 0
                    : (int) mAnnotationPaint.measureText(annotation, 0, annotation.length()) + X_GAP;

            paint.setColor(mColorNormal);
            if (mHaveMinimalSuggestion 
                    && ((i == 1 && !typedWordValid) || (i == 0 && typedWordValid))) {
                paint.setTypeface(Typeface.DEFAULT_BOLD);
                paint.setColor(mColorRecommended);
                existsAutoCompletion = true;
            } else if (i != 0 || (wordLength == 1 && count > 1)) {
                // HACK: even if i == 0, we use mColorOther when this suggestion's length is 1 and
                // there are multiple suggestions, such as the default punctuation list.
                paint.setColor(mColorOther);
            }
            int wordWidth;
            if ((wordWidth = mWordWidth[i]) == 0) {
                float textWidth =  paint.measureText(suggestion, 0, wordLength);
                wordWidth = Math.max(mMinTouchableWidth, (int) textWidth + X_GAP * 2 + annotationWidth);
                mWordWidth[i] = wordWidth;
            }

            mWordX[i] = x;

            // The engine's chosen candidate, highlighted as a touched one is (#903 H6).
            if (canvas != null && mShowingEngine && i == mBar.getFocused()) {
                canvas.translate(x, 0);
                mSelectionHighlight.setBounds(0, bgPadding.top, wordWidth, height);
                mSelectionHighlight.draw(canvas);
                canvas.translate(-x, 0);
            }

            if (touchX != OUT_OF_BOUNDS_X_COORD && !scrolled && !touchOnButton
                    && touchX + scrollX >= x && touchX + scrollX < x + wordWidth) {
                if (canvas != null && !mShowingAddToDictionary) {
                    canvas.translate(x, 0);
                    mSelectionHighlight.setBounds(0, bgPadding.top, wordWidth, height);
                    mSelectionHighlight.draw(canvas);
                    canvas.translate(-x, 0);
                }
                mSelectedString = suggestion;
                mSelectedIndex = i;
            }

            if (canvas != null) {
                if (arriveAlpha < 1f) paint.setAlpha((int) (paint.getAlpha() * arriveAlpha));
                canvas.drawText(suggestion, 0, wordLength, x + (wordWidth - annotationWidth) / 2, y + arriveDrop, paint);
                if (annotation != null) {
                    mAnnotationPaint.setAlpha((int) (255 * arriveAlpha));
                    canvas.drawText(annotation, 0, annotation.length(),
                            x + wordWidth - X_GAP / 2 - annotationWidth / 2, y + arriveDrop, mAnnotationPaint);
                }
                if (ring && i == mBar.getSelected()) drawRing(canvas, x, wordWidth, height);
                paint.setColor(mColorOther);
                canvas.translate(x + wordWidth, 0);
                // Draw a divider unless it's after the hint
                if (!(mShowingAddToDictionary && i == 1)) {
                    mDivider.draw(canvas);
                }
                canvas.translate(-x - wordWidth, 0);
            }
            paint.setTypeface(Typeface.DEFAULT);
            x += wordWidth;
        }
        if (canvas != null && mArriving) {
            if (arriveAlpha >= 1f && arriveDrop <= 0f) mArriving = false;
            else postInvalidateOnAnimation();
        }
        if (canvas != null && expandable) drawExpandButton(canvas, scrollX, height, ring && mBar.getOnExpandButton());
        final CharSequence notice = mShowingEngine && count == 0 ? mBar.getNotice() : null;
        if (canvas != null && notice != null && notice.length() > 0) {
            // One plain line while the engine gets ready, or why it cannot work (#903 H7a).
            paint.setColor(mColorOther);
            canvas.drawText(notice, 0, notice.length(), mModeWidth + (getWidth() - mModeWidth) / 2, y, paint);
        }
        if (canvas != null && modeLabel != null) drawModeButton(canvas, scrollX, height, modeLabel);
        if (!isInEditMode())
            mService.onAutoCompletionStateChanged(existsAutoCompletion);
        // With the button, the row scrolls far enough to bring its last word out from under it.
        mTotalWidth = expandable ? x + mExpandWidth : x;
        if (mTargetScrollX != scrollX) {
            scrollToTarget();
        }
    }
    
    private void scrollToTarget() {
        int scrollX = getScrollX();
        if (mTargetScrollX > scrollX) {
            scrollX += SCROLL_PIXELS;
            if (scrollX >= mTargetScrollX) {
                scrollX = mTargetScrollX;
                scrollTo(scrollX, getScrollY());
                requestLayout();
            } else {
                scrollTo(scrollX, getScrollY());
            }
        } else {
            scrollX -= SCROLL_PIXELS;
            if (scrollX <= mTargetScrollX) {
                scrollX = mTargetScrollX;
                scrollTo(scrollX, getScrollY());
                requestLayout();
            } else {
                scrollTo(scrollX, getScrollY());
            }
        }
        invalidate();
    }
    
    public void setSuggestions(List<CharSequence> suggestions, boolean completions,
            boolean typedWordValid, boolean haveMinimalSuggestion) {
        fill(suggestions, null, completions, typedWordValid, haveMinimalSuggestion);
        // Hacker's own suggestions: plain words, no grid (#903 H6). The model keeps them for the pad.
        final boolean padActive = mBar.getPadActive();
        mShowingEngine = false;
        mBar = mOwnBar;
        mBar.show(InputEngine.Candidates.of(mSuggestions), false);
        if (padActive && !mBar.getPadActive()) mBar.padEnter();
        hideGrid();
    }

    /**
     * droidtop patch (Droidtop/tracker#903 H6): a language's own engine's candidates, from the bar's model: the same
     * row, the same arrival motion, with each candidate's annotation drawn small, its chosen one highlighted, and the
     * expand button when the engine converts. The row holds the first MAX_SUGGESTIONS; the grid holds them all.
     */
    void showEngineCandidates(CandidateBar bar) {
        final List<InputEngine.Candidate> items = bar.getItems();
        final ArrayList<CharSequence> words = new ArrayList<CharSequence>(items.size());
        final ArrayList<CharSequence> annotations = new ArrayList<CharSequence>(items.size());
        for (InputEngine.Candidate candidate : items) {
            words.add(candidate.getText());
            annotations.add(candidate.getAnnotation());
        }
        final boolean padActive = mBar.getPadActive();
        mBar = bar;
        if (padActive && !bar.getPadActive()) bar.padEnter();
        mShowingEngine = true;
        fill(words, annotations, false, false, false);
        syncGrid();
    }

    /** Whether the row shows a language's own engine's candidates (so a pick is the engine's). */
    boolean isShowingEngineCandidates() {
        return mShowingEngine;
    }

    private void fill(List<CharSequence> suggestions, List<CharSequence> annotations, boolean completions,
            boolean typedWordValid, boolean haveMinimalSuggestion) {
        // droidtop patch (#903 H12a): a list that differs from the one shown arrives with a short fade and rise.
        if (suggestions != null && !suggestions.isEmpty() && !sameAsShown(suggestions) && KeyboardMotion.enabled()) {
            mArriving = true;
            mArrivedAt = android.view.animation.AnimationUtils.currentAnimationTimeMillis();
        }
        clear();
        if (suggestions != null) {
            int insertCount = Math.min(suggestions.size(), MAX_SUGGESTIONS);
            for (CharSequence suggestion : suggestions) {
                mSuggestions.add(suggestion);
                if (--insertCount == 0)
                    break;
            }
        }
        if (annotations != null) {
            for (int i = 0; i < mSuggestions.size() && i < annotations.size(); i++) mAnnotations.add(annotations.get(i));
        }
        mShowingCompletions = completions;
        mTypedWordValid = typedWordValid;
        scrollTo(0, getScrollY());
        mTargetScrollX = 0;
        mHaveMinimalSuggestion = haveMinimalSuggestion;
        // Compute the total width
        onDraw(null);
        invalidate();
        requestLayout();
    }

    /** Whether [suggestions] are the ones already shown, word for word, up to what the strip holds. */
    private boolean sameAsShown(List<CharSequence> suggestions) {
        final int count = Math.min(suggestions.size(), MAX_SUGGESTIONS);
        if (count != mSuggestions.size()) return false;
        for (int i = 0; i < count; i++) {
            if (!android.text.TextUtils.equals(suggestions.get(i), mSuggestions.get(i))) return false;
        }
        return true;
    }

    public boolean isShowingAddToDictionaryHint() {
        return mShowingAddToDictionary;
    }

    public void showAddToDictionaryHint(CharSequence word) {
        ArrayList<CharSequence> suggestions = new ArrayList<CharSequence>();
        suggestions.add(word);
        suggestions.add(mAddToDictionaryHint);
        setSuggestions(suggestions, false, false, false);
        mShowingAddToDictionary = true;
    }

    public boolean dismissAddToDictionaryHint() {
        if (!mShowingAddToDictionary) return false;
        clear();
        return true;
    }

    /* package */ List<CharSequence> getSuggestions() {
        return mSuggestions;
    }

    public void clear() {
        // Don't call mSuggestions.clear() because it's being used for logging
        // in LatinIME.pickSuggestionManually().
        mSuggestions.clear();
        mAnnotations.clear();
        mTouchX = OUT_OF_BOUNDS_X_COORD;
        mSelectedString = null;
        mSelectedIndex = -1;
        mShowingAddToDictionary = false;
        invalidate();
        Arrays.fill(mWordWidth, 0);
        Arrays.fill(mWordX, 0);
    }
    
    @Override
    public boolean onTouchEvent(MotionEvent me) {
        // droidtop patch (#903 H6): a touch takes the pad's ring away, as it does on the keys.
        if (me.getAction() == MotionEvent.ACTION_DOWN && mBar.getPadActive()) {
            mBar.padLeave();
            invalidateBar();
        }
        if (mGridMode) {
            return onGridTouch(me);
        }

        if (mGestureDetector.onTouchEvent(me)) {
            return true;
        }

        int action = me.getAction();
        int x = (int) me.getX();
        int y = (int) me.getY();
        mTouchX = x;

        switch (action) {
        case MotionEvent.ACTION_DOWN:
            invalidate();
            break;
        case MotionEvent.ACTION_MOVE:
            if (y <= 0) {
                // Fling up!?
                if (mSelectedString != null) {
                    // If there are completions from the application, we don't change the state to
                    // STATE_PICKED_SUGGESTION
                    if (!mShowingCompletions) {
                        // This "acceptedSuggestion" will not be counted as a word because
                        // it will be counted in pickSuggestion instead.
                        //TextEntryState.acceptedSuggestion(mSuggestions.get(0), mSelectedString);
                        //TextEntryState.manualTyped(mSelectedString);
                    }
                    mService.pickSuggestionManually(mSelectedIndex, mSelectedString);
                    mSelectedString = null;
                    mSelectedIndex = -1;
                }
            }
            break;
        case MotionEvent.ACTION_UP:
            if (!mScrolled && mShowingEngine && mBar.getExpandable() && x >= getWidth() - mExpandWidth) {
                // The expand button (#903 H6).
                toggleGrid();
            } else if (!mScrolled && x < mModeWidth) {
                // The mode button (#903 H7a): the engine's next composition mode.
                mService.cycleEngineMode();
            } else if (!mScrolled) {
                if (mSelectedString != null) {
                    if (mShowingAddToDictionary) {
                        longPressFirstWord();
                        clear();
                    } else {
                        if (!mShowingCompletions) {
                            //TextEntryState.acceptedSuggestion(mSuggestions.get(0), mSelectedString);
                            //TextEntryState.manualTyped(mSelectedString);
                        }
                        mService.pickSuggestionManually(mSelectedIndex, mSelectedString);
                    }
                }
            }
            mSelectedString = null;
            mSelectedIndex = -1;
            requestLayout();
            hidePreview();
            invalidate();
            break;
        }
        return true;
    }

    private void hidePreview() {
        mTouchX = OUT_OF_BOUNDS_X_COORD;
        mCurrentWordIndex = OUT_OF_BOUNDS_WORD_INDEX;
        mPreviewPopup.dismiss();
    }
    
    private void showPreview(int wordIndex, String altText) {
        int oldWordIndex = mCurrentWordIndex;
        mCurrentWordIndex = wordIndex;
        // If index changed or changing text
        if (oldWordIndex != mCurrentWordIndex || altText != null) {
            if (wordIndex == OUT_OF_BOUNDS_WORD_INDEX) {
                hidePreview();
            } else {
                CharSequence word = altText != null? altText : mSuggestions.get(wordIndex);
                mPreviewText.setText(word);
                mPreviewText.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), 
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                int wordWidth = (int) (mPaint.measureText(word, 0, word.length()) + X_GAP * 2);
                final int popupWidth = wordWidth
                        + mPreviewText.getPaddingLeft() + mPreviewText.getPaddingRight();
                final int popupHeight = mPreviewText.getMeasuredHeight();
                //mPreviewText.setVisibility(INVISIBLE);
                mPopupPreviewX = mWordX[wordIndex] - mPreviewText.getPaddingLeft() - getScrollX()
                        + (mWordWidth[wordIndex] - wordWidth) / 2;
                mPopupPreviewY = - popupHeight;
                int [] offsetInWindow = new int[2];
                getLocationInWindow(offsetInWindow);
                if (mPreviewPopup.isShowing()) {
                    mPreviewPopup.update(mPopupPreviewX, mPopupPreviewY + offsetInWindow[1], 
                            popupWidth, popupHeight);
                } else {
                    mPreviewPopup.setWidth(popupWidth);
                    mPreviewPopup.setHeight(popupHeight);
                    mPreviewPopup.showAtLocation(this, Gravity.NO_GRAVITY, mPopupPreviewX, 
                            mPopupPreviewY + offsetInWindow[1]);
                }
                mPreviewText.setVisibility(VISIBLE);
            }
        }
    }

    private void longPressFirstWord() {
        CharSequence word = mSuggestions.get(0);
        if (word.length() < 2) return;
        if (mService.addWordToDictionary(word.toString())) {
            showPreview(0, getContext().getResources().getString(R.string.added_word, word));
        }
    }
    
    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        hidePreview();
        hideGrid();
    }

    // droidtop patch (Droidtop/tracker#903 H6): the expand button, the grid, and the pad's route onto the bar.

    private void drawRing(Canvas canvas, int x, int width, int height) {
        final float inset = mRingPaint.getStrokeWidth() / 2f;
        final float radius = mRingPaint.getStrokeWidth() * 2f;
        canvas.drawRoundRect(x + inset, inset, x + width - inset, height - inset, radius, radius, mRingPaint);
    }

    /** The button over the row's right end, on the strip's own ground, so the words scroll under it. */
    private void drawExpandButton(Canvas canvas, int scrollX, int height, boolean ringed) {
        final int left = scrollX + getWidth() - mExpandWidth;
        canvas.save();
        canvas.clipRect(left, 0, left + mExpandWidth, height);
        final View parent = getParent() instanceof View ? (View) getParent() : null;
        final Drawable ground = parent != null ? parent.getBackground() : null;
        if (ground != null) {
            canvas.translate(scrollX - getLeft(), -getTop());
            ground.draw(canvas);
            canvas.translate(getLeft() - scrollX, getTop());
        }
        canvas.translate(left, 0);
        mDivider.draw(canvas);
        canvas.translate(-left, 0);
        canvas.restore();
        mPaint.setColor(mColorNormal);
        mPaint.setAlpha(255);
        final String glyph = mBar.getExpanded() ? COLLAPSE_GLYPH : EXPAND_GLYPH;
        canvas.drawText(glyph, left + mExpandWidth / 2, (int) (height + mPaint.getTextSize() - mDescent) / 2, mPaint);
        if (ringed) drawRing(canvas, left, mExpandWidth, height);
    }

    /** The engine's mode over the row's left end, on the strip's own ground, so the words scroll under it (#903 H7a). */
    private void drawModeButton(Canvas canvas, int scrollX, int height, CharSequence label) {
        canvas.save();
        canvas.clipRect(scrollX, 0, scrollX + mModeWidth, height);
        final View parent = getParent() instanceof View ? (View) getParent() : null;
        final Drawable ground = parent != null ? parent.getBackground() : null;
        if (ground != null) {
            canvas.translate(scrollX - getLeft(), -getTop());
            ground.draw(canvas);
            canvas.translate(getLeft() - scrollX, getTop());
        }
        canvas.translate(scrollX + mModeWidth - mDivider.getIntrinsicWidth(), 0);
        mDivider.draw(canvas);
        canvas.restore();
        mPaint.setColor(mColorNormal);
        mPaint.setAlpha(255);
        canvas.drawText(label, 0, label.length(), scrollX + mModeWidth / 2,
                (int) (height + mPaint.getTextSize() - mDescent) / 2, mPaint);
    }

    private void toggleGrid() {
        mBar.toggle();
        syncGrid();
        invalidate();
    }

    /** Opens, updates or closes the grid to match the model. */
    private void syncGrid() {
        if (mGridMode) return;
        if (!mShowingEngine || !mBar.getExpanded() || getWindowToken() == null || !isShown()) {
            hideGrid();
            invalidate();
            return;
        }
        final int width = getWidth();
        int height = mService != null ? mService.candidateGridHeight() : 0;
        if (height <= 0) height = mRowHeight * 4;
        if (mGrid == null) {
            mGrid = new CandidateView(getContext(), null);
            mGrid.mGridMode = true;
            mGrid.setService(mService);
            mGridPopup = new PopupWindow(getContext());
            mGridPopup.setContentView(mGrid);
            final View parent = getParent() instanceof View ? (View) getParent() : null;
            final Drawable ground = parent != null && parent.getBackground() != null
                    && parent.getBackground().getConstantState() != null
                    ? parent.getBackground().getConstantState().newDrawable(getResources()) : null;
            mGridPopup.setBackgroundDrawable(ground != null ? ground
                    : new android.graphics.drawable.ColorDrawable(KeyboardAppearance.colors(getContext()).getGround()));
            mGridPopup.setAnimationStyle(0);
            mGridPopup.setClippingEnabled(false);
        }
        mGrid.mBar = mBar;
        mGrid.mShowingEngine = true;
        mGrid.invalidate();
        final int[] at = new int[2];
        getLocationInWindow(at);
        if (mGridPopup.isShowing()) {
            mGridPopup.update(at[0], at[1] + getHeight(), width, height);
        } else {
            mGridPopup.setWidth(width);
            mGridPopup.setHeight(height);
            mGridPopup.showAtLocation(this, Gravity.NO_GRAVITY, at[0], at[1] + getHeight());
        }
    }

    private void hideGrid() {
        if (mGridPopup != null && mGridPopup.isShowing()) mGridPopup.dismiss();
    }

    private void invalidateBar() {
        invalidate();
        if (mGrid != null) mGrid.invalidate();
    }

    /** The grid: the model's page, [columns] across and as many rows as fit, each candidate with its note under it. */
    private void drawGrid(Canvas canvas) {
        final List<InputEngine.Candidate> items = mBar.getItems();
        final int width = getWidth();
        int widest = mMinTouchableWidth;
        for (InputEngine.Candidate candidate : items) {
            widest = Math.max(widest, (int) mPaint.measureText(candidate.getText(), 0, candidate.getText().length()) + X_GAP * 2);
        }
        mBar.layoutGrid(Math.max(1, width / widest), Math.max(1, getHeight() / mRowHeight));
        final int columns = mBar.getColumns();
        final int cell = width / columns;
        final kotlin.ranges.IntRange range = mBar.getPageRange();
        final boolean ring = mBar.getPadActive();
        if (ring) mRingPaint.setColor(KeyboardAppearance.colors(getContext()).getAccent());
        for (int i = range.getFirst(); i <= range.getLast(); i++) {
            final int slot = i - range.getFirst();
            final int x = (slot % columns) * cell;
            final int top = (slot / columns) * mRowHeight;
            final InputEngine.Candidate candidate = items.get(i);
            canvas.save();
            canvas.translate(0, top);
            if (i == mBar.getFocused()) {
                canvas.translate(x, 0);
                mSelectionHighlight.setBounds(0, 0, cell, mRowHeight);
                mSelectionHighlight.draw(canvas);
                canvas.translate(-x, 0);
            }
            final CharSequence text = candidate.getText();
            final CharSequence note = candidate.getAnnotation();
            mPaint.setColor(i == 0 ? mColorNormal : mColorOther);
            final int baseline = note == null ? (int) (mRowHeight + mPaint.getTextSize() - mDescent) / 2
                    : (int) (mRowHeight - mAnnotationPaint.getTextSize()) / 2 + mDescent;
            canvas.drawText(text, 0, text.length(), x + cell / 2, baseline, mPaint);
            if (note != null) {
                mAnnotationPaint.setAlpha(255);
                canvas.drawText(note, 0, note.length(), x + cell / 2, baseline + mAnnotationPaint.getTextSize() + mDescent, mAnnotationPaint);
            }
            if (ring && i == mBar.getSelected()) drawRing(canvas, x, cell, mRowHeight);
            canvas.restore();
        }
        if (mBar.getPageCount() > 1) {
            final String pages = (mBar.getPage() + 1) + "/" + mBar.getPageCount();
            mAnnotationPaint.setAlpha(255);
            canvas.drawText(pages, width - mExpandWidth / 2, getHeight() - mDescent, mAnnotationPaint);
        }
    }

    /** A drag in the grid turns the page, once per drag: up for the next, down for the one before. */
    private boolean onGridScroll(MotionEvent e1, MotionEvent e2) {
        if (mGridPaged || e1 == null) return true;
        final float dy = e2.getY() - e1.getY();
        if (Math.abs(dy) < mMinTouchableWidth / 2f) return true;
        mGridPaged = true;
        if (dy < 0) mBar.nextPage(); else mBar.previousPage();
        invalidate();
        return true;
    }

    private boolean onGridTouch(MotionEvent me) {
        if (me.getAction() == MotionEvent.ACTION_DOWN) mGridPaged = false;
        mGestureDetector.onTouchEvent(me);
        if (me.getAction() == MotionEvent.ACTION_UP && !mGridPaged && mService != null) {
            final int columns = mBar.getColumns();
            final int cell = Math.max(1, getWidth() / columns);
            final int column = Math.min(columns - 1, (int) me.getX() / cell);
            final int row = (int) me.getY() / mRowHeight;
            final kotlin.ranges.IntRange range = mBar.getPageRange();
            final int index = range.getFirst() + row * columns + column;
            if (row >= 0 && index >= range.getFirst() && index <= range.getLast()) {
                mService.pickSuggestionManually(index, mBar.getItems().get(index).getText());
            }
        }
        return true;
    }

    /** The pad's ring onto the bar, at the engine's chosen candidate or the first: false when the bar is empty. */
    boolean padEnter() {
        if (mSuggestions.isEmpty() || !mBar.padEnter()) return false;
        scrollToSelected();
        invalidateBar();
        return true;
    }

    void padLeave() {
        if (!mBar.getPadActive()) return;
        mBar.padLeave();
        if (mBar.collapse()) syncGrid();
        invalidateBar();
    }

    boolean isPadActive() {
        return mBar.getPadActive();
    }

    /** A pad direction on the bar: false when it leaves the bar (Down from the row). */
    boolean padMove(CandidateBar.Move move) {
        if (!mBar.move(move)) {
            padLeave();
            return false;
        }
        scrollToSelected();
        invalidateBar();
        return true;
    }

    /** A on the bar: the selected candidate is picked as a tap picks it; on the expand button, the grid opens or closes. */
    void padPress() {
        if (mBar.getOnExpandButton()) {
            toggleGrid();
            invalidateBar();
            return;
        }
        final int index = mBar.selectedCandidate();
        if (index < 0) return;
        if (mShowingAddToDictionary) {
            longPressFirstWord();
            clear();
            return;
        }
        final CharSequence word = mShowingEngine ? mBar.getItems().get(index).getText()
                : index < mSuggestions.size() ? mSuggestions.get(index) : null;
        if (word != null) mService.pickSuggestionManually(index, word);
    }

    /** B on the bar: closes the grid, else gives the ring back (false). */
    boolean padBack() {
        if (mBar.collapse()) {
            syncGrid();
            invalidateBar();
            return true;
        }
        padLeave();
        return false;
    }

    /** Scrolls the row, as Hacker's own scroll does, so the pad's selection is in sight. */
    private void scrollToSelected() {
        final int index = mBar.getSelected();
        final int visible = getWidth() - (mShowingEngine && mBar.getExpandable() ? mExpandWidth : 0);
        if (index < 0 || index >= mSuggestions.size() || visible <= 0) return;
        final int left = mWordX[index];
        final int right = left + mWordWidth[index];
        final int scrollX = getScrollX();
        if (left < scrollX + mModeWidth) {
            mTargetScrollX = Math.max(0, left - mModeWidth);
        } else if (right > scrollX + visible) {
            mTargetScrollX = Math.min(Math.max(0, mTotalWidth - getWidth()), right - visible);
        }
    }
}
