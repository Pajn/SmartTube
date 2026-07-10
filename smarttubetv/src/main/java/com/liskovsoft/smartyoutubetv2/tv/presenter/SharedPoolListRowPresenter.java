package com.liskovsoft.smartyoutubetv2.tv.presenter;

import androidx.leanback.widget.ListRow;
import androidx.leanback.widget.ListRowPresenter;
import androidx.leanback.widget.ObjectAdapter;
import androidx.leanback.widget.Presenter;
import androidx.leanback.widget.RowPresenter;
import androidx.recyclerview.widget.RecyclerView;

import java.util.HashMap;
import java.util.Map;

/**
 * {@link CustomListRowPresenter} that shares a card {@link RecyclerView.RecycledViewPool} across
 * all rows so a row scrolling into view (or a freshly appended suggestions row) can reuse
 * already-inflated cards instead of inflating fresh ones - a source of jank while a video decodes.
 *
 * <p>Every card is leanback view type 0 in its own single-presenter row adapter, so views can only
 * be reused correctly among rows that share the same presenter (e.g. normal cards and shorts cards
 * are the same view class but bind different dimensions). The pool is therefore keyed by presenter
 * instance: each presenter gets its own pool, guaranteeing a view is never handed to a row that
 * would bind it differently.
 *
 * <p>Assumes each row adapter uses a single presenter (as the in-player suggestion rows do). Rows
 * with a mixed presenter selector are left on their default per-row pool.
 */
public class SharedPoolListRowPresenter extends CustomListRowPresenter {
    // Kept generous so a new row can be filled entirely from the pool rather than re-inflating.
    private static final int MAX_RECYCLED_CARDS = 16;
    private final Map<Presenter, RecyclerView.RecycledViewPool> mPools = new HashMap<>();

    @Override
    protected void onBindRowViewHolder(RowPresenter.ViewHolder holder, Object item) {
        // Assign the pool before super attaches the row adapter so the initial layout already pulls
        // recycled cards from it. On rebind of a recycled row view the previous adapter is already
        // detached (its cards recycled into the pool active then), so swapping pools can't mix types.
        applySharedPool(holder, item);

        super.onBindRowViewHolder(holder, item);
    }

    private void applySharedPool(RowPresenter.ViewHolder holder, Object item) {
        if (!(holder instanceof ListRowPresenter.ViewHolder) || !(item instanceof ListRow)) {
            return;
        }

        ObjectAdapter adapter = ((ListRow) item).getAdapter();

        if (adapter == null || adapter.size() == 0) {
            return;
        }

        Presenter presenter = adapter.getPresenter(adapter.get(0));

        if (presenter == null) {
            return;
        }

        RecyclerView.RecycledViewPool pool = mPools.get(presenter);

        if (pool == null) {
            pool = new RecyclerView.RecycledViewPool();
            pool.setMaxRecycledViews(0, MAX_RECYCLED_CARDS); // type 0 = single presenter per row adapter
            mPools.put(presenter, pool);
        }

        ((ListRowPresenter.ViewHolder) holder).getGridView().setRecycledViewPool(pool);
    }
}
