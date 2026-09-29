/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.launcher3.allapps.search;

import static android.view.View.MeasureSpec.EXACTLY;
import static android.view.View.MeasureSpec.getSize;
import static android.view.View.MeasureSpec.makeMeasureSpec;

import static com.android.launcher3.Utilities.prefixTextWithIcon;
import static com.android.launcher3.icons.IconNormalizer.ICON_VISIBLE_AREA_FACTOR;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup.MarginLayoutParams;

import com.android.launcher3.DeviceProfile;
import com.android.launcher3.ExtendedEditText;
import com.android.launcher3.Insettable;
import com.android.launcher3.R;
import com.android.launcher3.allapps.ActivityAllAppsContainerView;
import com.android.launcher3.allapps.SearchUiManager;
import com.android.launcher3.views.ActivityContext;

import dev.droidtop.shell.standard.LauncherSearch;

/**
 * The drawer's search field. It is a door, not a filter: a tap on it, Enter
 * on it, or a printable key typed in the drawer opens droidtop's search
 * screen ({@link LauncherSearch}), the shared library search with plugin
 * "Get more" results and Recommendations, so the drawer never filters its
 * own list in place and there is one search, not two.
 */
public class AppsSearchContainerLayout extends ExtendedEditText
        implements SearchUiManager, Insettable {
    private final ActivityContext mLauncher;

    private ActivityAllAppsContainerView<?> mAppsView;

    // The amount of pixels to shift down and overlap with the rest of the content.
    private final int mContentOverlap;

    public AppsSearchContainerLayout(Context context) {
        this(context, null);
    }

    public AppsSearchContainerLayout(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public AppsSearchContainerLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        mLauncher = ActivityContext.lookupContext(context);
        setHint(prefixTextWithIcon(getContext(), R.drawable.ic_allapps_search, getHint()));

        mContentOverlap =
                getResources().getDimensionPixelSize(R.dimen.all_apps_search_bar_content_overlap);

        // A button that looks like the field: no keyboard, no cursor, and a
        // touch is a click (not a focus first), so one tap opens the search.
        setInputType(android.text.InputType.TYPE_NULL);
        setShowSoftInputOnFocus(false);
        setCursorVisible(false);
        setLongClickable(false);
        setFocusableInTouchMode(false);
        setOnClickListener(v -> LauncherSearch.open(getContext()));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // Update the width to match the grid padding
        DeviceProfile dp = mLauncher.getDeviceProfile();
        int myRequestedWidth = getSize(widthMeasureSpec);
        int rowWidth = myRequestedWidth - mAppsView.getActiveRecyclerView().getPaddingLeft()
                - mAppsView.getActiveRecyclerView().getPaddingRight();

        int cellWidth = DeviceProfile.calculateCellWidth(rowWidth,
                dp.cellLayoutBorderSpacePx.x, dp.numShownHotseatIcons);
        int iconVisibleSize = Math.round(ICON_VISIBLE_AREA_FACTOR * dp.iconSizePx);
        int iconPadding = cellWidth - iconVisibleSize;

        int myWidth = rowWidth - iconPadding + getPaddingLeft() + getPaddingRight();
        super.onMeasure(makeMeasureSpec(myWidth, EXACTLY), heightMeasureSpec);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);

        // Shift the widget horizontally so that its centered in the parent (b/63428078)
        View parent = (View) getParent();
        int availableWidth = parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight();
        int myWidth = right - left;
        int expectedLeft = parent.getPaddingLeft() + (availableWidth - myWidth) / 2;
        int shift = expectedLeft - left;
        setTranslationX(shift);

        if (!mLauncher.getDeviceProfile().shouldShowAllAppsOnSheet())
            offsetTopAndBottom(mContentOverlap);
    }

    @Override
    public void initializeSearch(ActivityAllAppsContainerView<?> appsView) {
        mAppsView = appsView;
    }

    @Override
    public void resetSearch() {
        // Nothing is typed here, so there is no session to close.
    }

    @Override
    public void preDispatchKeyEvent(KeyEvent event) {
        // A printable key typed on a hardware keyboard while the drawer is up
        // starts a search with that character, as typing in the drawer always did.
        if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() != 0) return;
        final int unicodeChar = event.getUnicodeChar();
        final boolean isKeyNotWhitespace = unicodeChar > 0
                && !Character.isWhitespace(unicodeChar) && !Character.isSpaceChar(unicodeChar);
        if (isKeyNotWhitespace && !event.isCtrlPressed() && !event.isAltPressed()
                && !event.isMetaPressed()) {
            LauncherSearch.open(getContext(), new String(Character.toChars(unicodeChar)));
        }
    }

    @Override
    public void setInsets(Rect insets) {
        MarginLayoutParams mlp = (MarginLayoutParams) getLayoutParams();
        mlp.topMargin = mLauncher.getDeviceProfile().shouldShowAllAppsOnSheet() ?
                getResources().getDimensionPixelSize(R.dimen.bottom_sheet_handle_area_height) :
                insets.top;
        requestLayout();
    }

    @Override
    public ExtendedEditText getEditText() {
        return this;
    }
}
