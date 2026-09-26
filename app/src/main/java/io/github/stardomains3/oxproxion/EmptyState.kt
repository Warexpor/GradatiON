package io.github.stardomains3.oxproxion

import android.view.View
import androidx.recyclerview.widget.RecyclerView

/** Shows [emptyView] whenever the list's adapter has no items. */
object EmptyState {
    fun bind(recycler: RecyclerView, emptyView: View) {
        val adapter = recycler.adapter ?: return
        fun update() {
            emptyView.visibility = if (adapter.itemCount == 0) View.VISIBLE else View.GONE
        }
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() = update()
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = update()
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = update()
        })
        update()
    }
}
