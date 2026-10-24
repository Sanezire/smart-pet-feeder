package com.smartpetfeeder.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.smartpetfeeder.R;
import com.smartpetfeeder.model.AppNotificationItem;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class NotificationAdapter extends RecyclerView.Adapter<NotificationAdapter.ViewHolder> {
    public interface SelectionListener {
        void onSelectionChanged(boolean selectionMode, int selectedCount);
    }

    private List<AppNotificationItem> items = new ArrayList<>();
    private boolean selectionMode = false;
    private final Set<String> selectedIds = new HashSet<>();
    private SelectionListener selectionListener;

    public void setSelectionListener(SelectionListener l) {
        this.selectionListener = l;
    }

    public boolean isSelectionMode() {
        return selectionMode;
    }

    public void setSelectionMode(boolean enabled) {
        selectionMode = enabled;
        if (!enabled) selectedIds.clear();
        notifyDataSetChanged();
        dispatchSelection();
    }

    public List<String> getSelectedIds() {
        return new ArrayList<>(selectedIds);
    }

    public void selectAll() {
        for (AppNotificationItem item : items) selectedIds.add(item.getId());
        notifyDataSetChanged();
        dispatchSelection();
    }

    public int getItemTotal() {
        return items.size();
    }

    private void toggle(int position) {
        if (position < 0 || position >= items.size()) return;
        String id = items.get(position).getId();
        if (!selectedIds.remove(id)) selectedIds.add(id);
        notifyItemChanged(position);
        dispatchSelection();
    }

    private void dispatchSelection() {
        if (selectionListener != null) selectionListener.onSelectionChanged(selectionMode, selectedIds.size());
    }

    public void setItems(List<AppNotificationItem> next) {
        final List<AppNotificationItem> oldList = items;
        final List<AppNotificationItem> newList = next != null ? next : new ArrayList<>();
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return oldList.size();
            }

            @Override
            public int getNewListSize() {
                return newList.size();
            }

            @Override
            public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
                return oldList.get(oldItemPosition).getId()
                        .equals(newList.get(newItemPosition).getId());
            }

            @Override
            public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
                AppNotificationItem o = oldList.get(oldItemPosition);
                AppNotificationItem n = newList.get(newItemPosition);
                return o.getType() == n.getType()
                        && o.getTitle().equals(n.getTitle())
                        && o.getMessage().equals(n.getMessage())
                        && o.getTimeText().equals(n.getTimeText());
            }
        });
        items = newList;
        Set<String> liveIds = new HashSet<>();
        for (AppNotificationItem item : newList) liveIds.add(item.getId());
        boolean pruned = selectedIds.retainAll(liveIds);
        diff.dispatchUpdatesTo(this);
        if (pruned) dispatchSelection();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_notification, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(items.get(position));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    class ViewHolder extends RecyclerView.ViewHolder {
        private final TextView tvType;
        private final TextView tvTitle;
        private final TextView tvMessage;
        private final TextView tvTime;
        private final View vAccent;
        private final CheckBox cbSelect;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvType = itemView.findViewById(R.id.tvNotificationType);
            tvTitle = itemView.findViewById(R.id.tvNotificationTitle);
            tvMessage = itemView.findViewById(R.id.tvNotificationMessage);
            tvTime = itemView.findViewById(R.id.tvNotificationTime);
            vAccent = itemView.findViewById(R.id.viewNotificationAccent);
            cbSelect = itemView.findViewById(R.id.cbNotificationSelect);
            itemView.setOnClickListener(v -> {
                if (selectionMode) toggle(getAdapterPosition());
            });
            itemView.setOnLongClickListener(v -> {
                int pos = getAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return false;
                if (!selectionMode) {
                    selectionMode = true;
                    selectedIds.add(items.get(pos).getId());
                    notifyDataSetChanged();
                    dispatchSelection();
                } else {
                    toggle(pos);
                }
                return true;
            });
        }

        void bind(AppNotificationItem item) {
            tvTitle.setText(item.getTitle());
            tvMessage.setText(item.getMessage());
            tvTime.setText(item.getTimeText());
            cbSelect.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
            cbSelect.setChecked(selectedIds.contains(item.getId()));

            int color;
            String label;
            switch (item.getType()) {
                case ANOMALY:
                    color = ContextCompat.getColor(itemView.getContext(), R.color.colorStatusAnomaly);
                    label = itemView.getContext().getString(R.string.filter_anomaly);
                    break;
                case FEEDING:
                    color = ContextCompat.getColor(itemView.getContext(), R.color.colorPrimary);
                    label = itemView.getContext().getString(R.string.filter_feeding);
                    break;
                default:
                    color = ContextCompat.getColor(itemView.getContext(), R.color.colorStatusCompleted);
                    label = itemView.getContext().getString(R.string.filter_system);
                    break;
            }
            tvType.setText(label);
            tvType.setTextColor(color);
            vAccent.setBackgroundColor(color);
        }
    }
}
