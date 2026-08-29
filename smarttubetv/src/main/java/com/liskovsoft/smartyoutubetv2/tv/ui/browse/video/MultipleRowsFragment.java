package com.liskovsoft.smartyoutubetv2.tv.ui.browse.video;

import android.os.Bundle;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.leanback.app.RowsSupportFragment;
import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.ClassPresenterSelector;
import androidx.leanback.widget.HeaderItem;
import androidx.leanback.widget.ListRow;
import androidx.leanback.widget.ListRowPresenter;
import androidx.leanback.widget.OnItemViewSelectedListener;
import androidx.leanback.widget.Presenter;
import androidx.leanback.widget.Row;
import androidx.leanback.widget.RowPresenter;
import androidx.leanback.widget.RowPresenter.ViewHolder;
import androidx.leanback.widget.VerticalGridView;
import androidx.recyclerview.widget.RecyclerView;

import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.interfaces.VideoGroupPresenter;
import com.liskovsoft.smartyoutubetv2.common.prefs.MainUIData;
import com.liskovsoft.smartyoutubetv2.tv.adapter.VideoGroupObjectAdapter;
import com.liskovsoft.smartyoutubetv2.tv.presenter.ChannelHeaderPresenter;
import com.liskovsoft.smartyoutubetv2.tv.presenter.ChannelHeaderPresenter.ChannelHeaderCallback;
import com.liskovsoft.smartyoutubetv2.tv.presenter.SharedPoolListRowPresenter;
import com.liskovsoft.smartyoutubetv2.tv.presenter.ShortsCardPresenter;
import com.liskovsoft.smartyoutubetv2.tv.presenter.VideoCardPresenter;
import com.liskovsoft.smartyoutubetv2.tv.presenter.base.OnItemLongPressedListener;
import com.liskovsoft.smartyoutubetv2.tv.ui.browse.interfaces.VideoSection;
import com.liskovsoft.smartyoutubetv2.tv.ui.common.LeanbackActivity;
import com.liskovsoft.smartyoutubetv2.tv.ui.common.UriBackgroundManager;
import com.liskovsoft.smartyoutubetv2.tv.util.ViewUtil;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public abstract class MultipleRowsFragment extends RowsSupportFragment implements VideoSection {
    private static final String TAG = MultipleRowsFragment.class.getSimpleName();
    /**
     * How long after the last focus move new rows/cards may still be inserted into the grid.
     * Mid-scroll insertions request a full layout pass of the grid - a jank source.
     */
    private static final long SELECTION_SETTLE_DELAY_MS = 400;
    /**
     * Don't re-request the same page continuation more often than this.
     */
    private static final long CONTINUE_REQUEST_DEDUP_MS = 5_000;
    private UriBackgroundManager mBackgroundManager;
    private ArrayObjectAdapter mRowsAdapter;
    private ListRowPresenter mRowPresenter;
    private Map<Integer, VideoGroupObjectAdapter> mVideoGroupAdapters;
    private final List<VideoGroup> mPendingUpdates = new ArrayList<>();
    private final List<VideoGroup> mPendingScrollUpdates = new ArrayList<>();
    private final Map<Integer, Long> mContinueRequestTimes = new HashMap<>();
    private long mLastSelectionTimeMs;
    private final Runnable mApplyPendingScrollUpdates = this::applyPendingScrollUpdates;
    private VideoGroupPresenter mMainPresenter;
    private VideoCardPresenter mCardPresenter;
    private ShortsCardPresenter mShortsPresenter;
    private int mSelectedRowIndex = -1;
    private ChannelHeaderCallback mChannelHeaderCallback;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        mMainPresenter = getMainPresenter();
        mCardPresenter = new VideoCardPresenter();
        mShortsPresenter = new ShortsCardPresenter();
        mBackgroundManager = ((LeanbackActivity) getActivity()).getBackgroundManager();

        setupAdapter();
        setupEventListeners();
        applyPendingUpdates();
    }

    protected void addHeader(ChannelHeaderCallback callback) {
        mChannelHeaderCallback = callback;
    }

    protected abstract VideoGroupPresenter getMainPresenter();

    private void applyPendingUpdates() {
        // prevent modification within update method
        List<VideoGroup> copyArray = new ArrayList<>(mPendingUpdates);

        mPendingUpdates.clear();

        for (VideoGroup group : copyArray) {
            update(group);
        }
    }

    private void setupAdapter() {
        if (mVideoGroupAdapters == null) {
            mVideoGroupAdapters = new HashMap<>();
        }

        if (mRowsAdapter == null) {
            mRowPresenter = new SharedPoolListRowPresenter();
            mRowPresenter.enableChildRoundedCorners(getMainUIData().isUiTweakEnabled(MainUIData.UI_TWEAK_ROUNDED_CORNERS));

            ClassPresenterSelector presenterSelector = new ClassPresenterSelector();
            presenterSelector.addClassPresenter(ListRow.class, mRowPresenter);
            presenterSelector.addClassPresenter(ChannelHeaderCallback.class, new ChannelHeaderPresenter());

            mRowsAdapter = new ArrayObjectAdapter(presenterSelector);
            setAdapter(mRowsAdapter);
        }
    }

    private void setupEventListeners() {
        setOnItemViewClickedListener(new ItemViewClickedListener());
        setOnItemViewSelectedListener(new ItemViewSelectedListener());
        mCardPresenter.setOnItemViewLongPressedListener(new ItemViewLongPressedListener());
        mShortsPresenter.setOnItemViewLongPressedListener(new ItemViewLongPressedListener());
    }

    @Override
    public void clear() {
        if (mRowsAdapter != null) {
            mRowsAdapter.clear();
            if (mChannelHeaderCallback != null) {
                mRowsAdapter.add(mChannelHeaderCallback);
            }
        }

        if (mVideoGroupAdapters != null) {
            mVideoGroupAdapters.clear();
        }

        // Reset the position (bug appeared after fragment been reused)
        setPosition(mChannelHeaderCallback != null ? 1 : 0);
    }

    private void removeByIndex(int idx) {
        if (mRowsAdapter != null && mRowsAdapter.size() > idx) {
            ListRow row = (ListRow) mRowsAdapter.get(idx);
            mRowsAdapter.remove(row);
            VideoGroupObjectAdapter group = (VideoGroupObjectAdapter) row.getAdapter();
            mVideoGroupAdapters.values().remove(group);
        }
    }

    private void removeById(int id) {
        if (mRowsAdapter != null) {
            VideoGroupObjectAdapter needed = mVideoGroupAdapters.get(id);
            for (int i = 0; i < mRowsAdapter.size(); i++) {
                Object row = mRowsAdapter.get(i);

                if (row instanceof ListRow) {
                    VideoGroupObjectAdapter adapter = (VideoGroupObjectAdapter) ((ListRow) row).getAdapter();
                    if (adapter == needed) {
                        mRowsAdapter.remove(row);
                        mVideoGroupAdapters.remove(id);
                    }
                }
            }
        }
    }

    private int findPositionById(int id) {
        if (mRowsAdapter != null) {
            VideoGroupObjectAdapter needed = mVideoGroupAdapters.get(id);
            for (int i = 0; i < mRowsAdapter.size(); i++) {
                Object row = mRowsAdapter.get(i);

                if (row instanceof ListRow) {
                    VideoGroupObjectAdapter adapter = (VideoGroupObjectAdapter) ((ListRow) row).getAdapter();
                    if (adapter == needed) {
                        return i;
                    }
                }
            }
        }

        return -1;
    }

    private boolean isComputingLayout(VideoGroup group) {
        int action = group.getAction();

        // Attempt to fix: IllegalStateException: Cannot call this method while RecyclerView is computing a layout or scrolling
        if ((action == VideoGroup.ACTION_SYNC || action == VideoGroup.ACTION_REPLACE) && getVerticalGridView() != null) {
            if (getVerticalGridView().isComputingLayout()) {
                return true;
            }
            int position = findPositionById(group.getId());
            if (position != -1) {
                RecyclerView.ViewHolder viewHolder = getVerticalGridView().findViewHolderForAdapterPosition(position);
                if (viewHolder != null) {
                    Object nestedRecyclerView = Helpers.getField(viewHolder, "mNestedRecyclerView");
                    if (nestedRecyclerView instanceof WeakReference) {
                        Object recyclerView = ((WeakReference<?>) nestedRecyclerView).get();
                        return recyclerView instanceof RecyclerView && ((RecyclerView) recyclerView).isComputingLayout();
                    }
                }
            }
        }

        return false;
    }

    @Override
    public boolean isEmpty() {
        if (mRowsAdapter == null) {
            return mPendingUpdates.isEmpty();
        }

        return mRowsAdapter.size() == 0;
    }

    @Override
    public void update(VideoGroup group) {
        if (isComputingLayout(group)) {
            return;
        }

        if (mVideoGroupAdapters == null) {
            mPendingUpdates.add(group);
            return;
        }

        // Correct position depending on the search bar presence
        if (group.getPosition() != -1 && mChannelHeaderCallback != null) {
            group.setPosition(group.getPosition() + 1);
        }

        int action = group.getAction();

        if (action == VideoGroup.ACTION_REPLACE) {
            if (group.getPosition() == -1) {
                clear();
            } else {
                removeById(group.getId());
            }
        } else if (action == VideoGroup.ACTION_REMOVE) {
            VideoGroupObjectAdapter adapter = mVideoGroupAdapters.get(group.getId());
            if (adapter != null) {
                adapter.remove(group);
            }
            return;
        } else if (action == VideoGroup.ACTION_SYNC) {
            VideoGroupObjectAdapter adapter = mVideoGroupAdapters.get(group.getId());
            if (adapter != null) {
                freeze(true);
                adapter.sync(group);
                freeze(false);
            }
            return;
        }

        if (group.isEmpty()) {
            return;
        }

        // Inserting rows/cards while the user is still navigating requests extra layout passes
        // of the grid. Wait until the selection settles instead.
        if (isUserActive()) {
            mPendingScrollUpdates.add(group);
            schedulePendingScrollUpdatesFlush();
            return;
        }

        VideoGroupObjectAdapter existingAdapter = GridFragmentHelper.findRelatedAdapter(mVideoGroupAdapters, group, this::freeze);

        if (existingAdapter == null) {
            HeaderItem rowHeader = new HeaderItem(group.getTitle());
            int videoGroupId = group.getId(); // Create unique int from category.

            VideoGroupObjectAdapter videoGroupAdapter = new VideoGroupObjectAdapter(group, group.isShorts() ? mShortsPresenter : mCardPresenter);

            mVideoGroupAdapters.put(videoGroupId, videoGroupAdapter);

            ListRow row = new ListRow(rowHeader, videoGroupAdapter);

            if (group.getPosition() == -1 || group.getPosition() > mRowsAdapter.size()) {
                mRowsAdapter.add(row);
            } else {
                mRowsAdapter.add(group.getPosition(), row);
            }
        } else {
            Log.d(TAG, "Continue row %s %s", group.getTitle(), System.currentTimeMillis());

            freeze(true);

            existingAdapter.add(group); // continue

            freeze(false);
        }

        restorePosition();
    }

    private void restorePosition() {
        setPosition(mSelectedRowIndex);

        // Maybe we don't need to load next group since all rows already fetched?
    }

    private boolean isUserActive() {
        VerticalGridView grid = getVerticalGridView();
        boolean isScrolling = grid != null && grid.getScrollState() != RecyclerView.SCROLL_STATE_IDLE;
        boolean isSelecting = System.currentTimeMillis() - mLastSelectionTimeMs < SELECTION_SETTLE_DELAY_MS;

        return isScrolling || isSelecting;
    }

    private void applyPendingScrollUpdates() {
        if (mPendingScrollUpdates.isEmpty()) {
            return;
        }

        // prevent modification within update method
        List<VideoGroup> copyArray = new ArrayList<>(mPendingScrollUpdates);
        mPendingScrollUpdates.clear();

        for (VideoGroup group : copyArray) {
            update(group);
        }
    }

    private void schedulePendingScrollUpdatesFlush() {
        Utils.removeCallbacks(mApplyPendingScrollUpdates);
        Utils.postDelayed(mApplyPendingScrollUpdates, SELECTION_SETTLE_DELAY_MS);
    }

    @Override
    public void onPause() {
        super.onPause();

        // Don't lose the content that arrived while the user was still navigating
        applyPendingScrollUpdates();
        Utils.removeCallbacks(mApplyPendingScrollUpdates);
    }

    @Override
    public int getPosition() {
        return getSelectedPosition();
    }

    @Override
    public void setPosition(int index) {
        if (index < 0) {
            return;
        }

        if (mRowsAdapter != null && index < mRowsAdapter.size()) {
            setSelectedPosition(index, false);
            mSelectedRowIndex = -1;
        } else {
            mSelectedRowIndex = index;
        }
    }

    @Override
    public void selectItem(Video item) {
        // NOP
    }

    /**
     * Disable scrolling on partially updated rows. This prevent cards from misbehaving.
     */
    private void freeze(boolean freeze) {
        // Disable scrolling on partially updated rows. This prevent controls from misbehaving.
        if (mRowPresenter != null) {
            ViewHolder vh = getRowViewHolder(getSelectedPosition());
            if (vh instanceof ListRowPresenter.ViewHolder) {
                mRowPresenter.freeze(vh, freeze);
            }
        }
    }

    private MainUIData getMainUIData() {
        return MainUIData.instance(getContext());
    }

    private final class ItemViewLongPressedListener implements OnItemLongPressedListener {
        @Override
        public void onItemLongPressed(Presenter.ViewHolder itemViewHolder, Object item) {

            if (item instanceof Video) {
                mMainPresenter.onVideoItemLongClicked((Video) item);
            } else {
                Toast.makeText(getActivity(), item.toString(), Toast.LENGTH_SHORT).show();
            }
        }
    }

    private final class ItemViewClickedListener implements androidx.leanback.widget.OnItemViewClickedListener {
        @Override
        public void onItemClicked(Presenter.ViewHolder itemViewHolder, Object item,
                                  RowPresenter.ViewHolder rowViewHolder, Row row) {

            if (item instanceof Video) {
                mMainPresenter.onVideoItemClicked((Video) item);
            } else {
                Toast.makeText(getActivity(), item.toString(), Toast.LENGTH_SHORT).show();
            }
        }
    }

    private final class ItemViewSelectedListener implements OnItemViewSelectedListener {
        @Override
        public void onItemSelected(Presenter.ViewHolder itemViewHolder, Object item,
                                   RowPresenter.ViewHolder rowViewHolder, Row row) {
            mLastSelectionTimeMs = System.currentTimeMillis();
            schedulePendingScrollUpdatesFlush();

            if (item instanceof Video) {
                mBackgroundManager.setBackgroundFrom((Video) item);

                mMainPresenter.onVideoItemSelected((Video) item);

                checkScrollEnd((Video)item);
            }
        }

        private void checkScrollEnd(Video item) {
            for (VideoGroupObjectAdapter adapter : mVideoGroupAdapters.values()) {
                int index = adapter.indexOf(item);

                if (index != -1) {
                    int size = adapter.size();
                    if (index > (size - ViewUtil.ROW_SCROLL_CONTINUE_NUM)) {
                        requestContinue(adapter, size);
                    }
                    break;
                }
            }
        }

        private void requestContinue(VideoGroupObjectAdapter adapter, int size) {
            Integer adapterKey = System.identityHashCode(adapter);
            long currentTimeMs = System.currentTimeMillis();
            Long lastRequestMs = mContinueRequestTimes.get(adapterKey);

            if (lastRequestMs != null && currentTimeMs - lastRequestMs < CONTINUE_REQUEST_DEDUP_MS) {
                return; // continuation is already loading
            }

            Video lastVideo = (Video) adapter.get(size - 1);
            VideoGroup lastGroup = lastVideo != null ? lastVideo.getGroup() : null;

            if (lastGroup == null || lastGroup.getMediaGroup() == null
                    || lastGroup.getMediaGroup().getNextPageKey() == null) {
                return; // no more pages in this row
            }

            mContinueRequestTimes.put(adapterKey, currentTimeMs);
            mMainPresenter.onScrollEnd(lastVideo);
        }
    }
}
