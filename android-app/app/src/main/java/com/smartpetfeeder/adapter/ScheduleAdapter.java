package com.smartpetfeeder.adapter;

import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.smartpetfeeder.R;
import com.smartpetfeeder.model.FeedingSchedule;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ScheduleAdapter extends RecyclerView.Adapter<ScheduleAdapter.ViewHolder> {

    public interface OnDeleteListener {
        void onDelete(FeedingSchedule schedule, int position);
    }

    public interface OnToggleListener {
        void onToggle(FeedingSchedule schedule, boolean enabled, int position);
    }

    public interface OnEditListener {
        void onEdit(FeedingSchedule schedule, int position);
    }

    private OnDeleteListener deleteListener;
    private OnToggleListener toggleListener;
    private OnEditListener   editListener;
    private boolean isReadOnly = false;
    private float recommendedPortion = 0f;

    public void setOnDeleteListener(OnDeleteListener l) { this.deleteListener = l; }
    public void setOnToggleListener(OnToggleListener l) { this.toggleListener = l; }
    public void setOnEditListener(OnEditListener l)     { this.editListener   = l; }
    public void setReadOnly(boolean readOnly) { this.isReadOnly = readOnly; }

    private java.util.Set<String> unappliedIds = new java.util.HashSet<>();

    public void setUnappliedIds(java.util.Set<String> ids) {
        java.util.Set<String> next = ids != null ? ids : new java.util.HashSet<>();
        if (next.equals(unappliedIds)) return;
        java.util.Set<String> changed = new java.util.HashSet<>(next);
        changed.addAll(unappliedIds);
        java.util.Set<String> both = new java.util.HashSet<>(next);
        both.retainAll(unappliedIds);
        changed.removeAll(both);
        unappliedIds = new java.util.HashSet<>(next);
        for (int i = 0; i < schedules.size(); i++) {
            String id = schedules.get(i).getScheduleId();
            if (id != null && changed.contains(id)) notifyItemChanged(i);
        }
    }

    public void setRecommendedPortion(float grams) {
        if (Math.abs(this.recommendedPortion - grams) < 0.01f) return;
        this.recommendedPortion = grams;
        if (!schedules.isEmpty()) notifyItemRangeChanged(0, schedules.size());
    }

    private List<FeedingSchedule> schedules = new ArrayList<>();

    public void setSchedules(List<FeedingSchedule> newList) {
        final List<FeedingSchedule> oldList = this.schedules;
        final List<FeedingSchedule> next    = (newList != null) ? newList : new ArrayList<>();

        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return oldList.size(); }
            @Override public int getNewListSize() { return next.size(); }

            @Override
            public boolean areItemsTheSame(int o, int n) {
                String oldId = oldList.get(o).getScheduleId();
                String newId = next.get(n).getScheduleId();
                return oldId != null && oldId.equals(newId);
            }

            @Override
            public boolean areContentsTheSame(int o, int n) {
                FeedingSchedule a = oldList.get(o);
                FeedingSchedule b = next.get(n);
                return a.getTimeHour()    == b.getTimeHour()
                        && a.getTimeMinute()  == b.getTimeMinute()
                        && a.getPortionGrams() == b.getPortionGrams()
                        && a.isEnabled()      == b.isEnabled()
                        && equalDays(a, b);
            }
        });

        this.schedules = next;
        diff.dispatchUpdatesTo(this);
    }

    private boolean equalDays(FeedingSchedule a, FeedingSchedule b) {
        List<String> da = a.getRepeatDays();
        List<String> db = b.getRepeatDays();
        if (da == null && db == null) return true;
        if (da == null || db == null) return false;
        return da.size() == db.size() && da.containsAll(db);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_schedule, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder h, int position) {
        FeedingSchedule s = schedules.get(position);

        h.tvTime.setText(FeedingSchedule.formatTime12(s.getTimeHour(), s.getTimeMinute()));

        h.tvPortion.setText(String.format(Locale.US,
                "%.0fg per feeding", s.getPortionGrams()));

        if (!isReadOnly && recommendedPortion > 0f) {
            int rec = Math.round(recommendedPortion);
            boolean matches = Math.round(s.getPortionGrams()) == rec;
            h.tvRecommended.setVisibility(View.VISIBLE);
            h.tvRecommended.setText(h.itemView.getContext().getString(
                    matches ? R.string.schedule_item_recommended_match
                            : R.string.schedule_item_recommended_custom, rec));
            h.tvRecommended.setTextColor(ContextCompat.getColor(h.itemView.getContext(),
                    matches ? R.color.colorStatusCompleted : R.color.colorStatusMissed));
        } else {
            h.tvRecommended.setVisibility(View.GONE);
        }

        h.tvRepeat.setText(s.getRepeatDisplay());

        h.tvUnapplied.setVisibility(
                !isReadOnly && s.getScheduleId() != null && unappliedIds.contains(s.getScheduleId())
                        ? View.VISIBLE : View.GONE);

        if (isReadOnly) {
            h.switchEnabled.setVisibility(View.GONE);
        } else {
            h.switchEnabled.setVisibility(View.VISIBLE);
            h.switchEnabled.setOnCheckedChangeListener(null);
            h.switchEnabled.setChecked(s.isEnabled());
            h.switchEnabled.setOnCheckedChangeListener((btn, checked) -> {
                int pos = h.getAdapterPosition();
                if (pos == RecyclerView.NO_ID) return;
                s.setEnabled(checked);
                applyEnabledStyle(h, checked);
                if (toggleListener != null) toggleListener.onToggle(s, checked, pos);
            });
        }

        applyEnabledStyle(h, s.isEnabled());

        if (!isReadOnly) {
            h.card.setOnClickListener(v -> {
                int pos = h.getAdapterPosition();
                if (pos == RecyclerView.NO_ID) return;
                if (editListener != null) editListener.onEdit(s, pos);
            });
        } else {
            h.card.setOnClickListener(null);
        }

        if (isReadOnly) {
            h.btnDelete.setVisibility(View.GONE);
        } else {
            h.btnDelete.setVisibility(View.VISIBLE);
            h.btnDelete.setOnClickListener(v -> {
                int pos = h.getAdapterPosition();
                if (pos == RecyclerView.NO_ID) return;
                if (deleteListener != null) deleteListener.onDelete(s, pos);
            });
        }
    }

    @Override public int getItemCount() { return schedules.size(); }

    private void applyEnabledStyle(ViewHolder h, boolean enabled) {
        int accentColor = enabled
                ? ContextCompat.getColor(h.itemView.getContext(), R.color.colorPrimary)
                : ContextCompat.getColor(h.itemView.getContext(), R.color.colorStroke);

        h.accent.setBackgroundColor(accentColor);
        h.tvTime.setAlpha(enabled ? 1f : 0.45f);
        h.tvPortion.setAlpha(enabled ? 1f : 0.45f);
        h.tvRepeat.setAlpha(enabled ? 1f : 0.45f);
        h.tvRecommended.setAlpha(enabled ? 1f : 0.45f);

        int cardBg = enabled
                ? ContextCompat.getColor(h.itemView.getContext(), R.color.colorSurface)
                : ContextCompat.getColor(h.itemView.getContext(), R.color.colorBackgroundDeep);
        h.card.setCardBackgroundColor(cardBg);
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final CardView        card;
        final View            accent;
        final TextView        tvTime;
        final TextView        tvPortion;
        final TextView        tvRepeat;
        final TextView        tvRecommended;
        final TextView        tvUnapplied;
        final SwitchMaterial  switchEnabled;
        final MaterialButton  btnDelete;

        ViewHolder(@NonNull View v) {
            super(v);
            card          = v.findViewById(R.id.cardSchedule);
            accent        = v.findViewById(R.id.scheduleAccent);
            tvTime        = v.findViewById(R.id.tvScheduleTime);
            tvPortion     = v.findViewById(R.id.tvSchedulePortion);
            tvRepeat      = v.findViewById(R.id.tvScheduleRepeat);
            tvRecommended = v.findViewById(R.id.tvScheduleRecommended);
            tvUnapplied   = v.findViewById(R.id.tvScheduleUnapplied);
            switchEnabled = v.findViewById(R.id.switchScheduleEnabled);
            btnDelete     = v.findViewById(R.id.btnDeleteSchedule);
        }
    }
}
