package com.smartpetfeeder.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.smartpetfeeder.R;
import com.smartpetfeeder.model.FeedingRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class FeedingHistoryAdapter extends RecyclerView.Adapter<FeedingHistoryAdapter.ViewHolder> {

    private List<FeedingRecord> records = new ArrayList<>();
    private final java.util.Map<String, Boolean> boundProgress = new java.util.HashMap<>();

    public void refreshAll() {
        notifyItemRangeChanged(0, records.size());
    }

    public void refreshProgress() {
        long now = System.currentTimeMillis();
        for (int i = 0; i < records.size(); i++) {
            FeedingRecord r = records.get(i);
            Boolean last = boundProgress.get(r.getFeedingId());
            if (last != null && last != r.isInProgress(now)) notifyItemChanged(i);
        }
    }

    public interface OnItemClickListener {
        void onItemClick(FeedingRecord record);
    }

    private OnItemClickListener itemClickListener;

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.itemClickListener = listener;
    }

    public void setRecords(List<FeedingRecord> newRecords) {
        final List<FeedingRecord> oldList = this.records;
        final List<FeedingRecord> newList = newRecords != null ? newRecords : new ArrayList<>();

        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return oldList.size(); }
            @Override public int getNewListSize() { return newList.size(); }

            @Override
            public boolean areItemsTheSame(int oldPos, int newPos) {
                FeedingRecord o = oldList.get(oldPos);
                FeedingRecord n = newList.get(newPos);
                return o.getFeedingId() != null && o.getFeedingId().equals(n.getFeedingId());
            }

            @Override
            public boolean areContentsTheSame(int oldPos, int newPos) {
                FeedingRecord o = oldList.get(oldPos);
                FeedingRecord n = newList.get(newPos);
                return o.getStatus().equals(n.getStatus())
                        && o.getScheduledTime() == n.getScheduledTime()
                        && o.getActualTime() == n.getActualTime()
                        && Float.compare(o.getScheduledPortion(), n.getScheduledPortion()) == 0
                        && Float.compare(o.getDispensedWeight(), n.getDispensedWeight()) == 0
                        && Float.compare(o.getConsumedWeight(), n.getConsumedWeight()) == 0
                        && Float.compare(o.getRemainingWeight(), n.getRemainingWeight()) == 0
                        && Float.compare(o.getWeightAfterDispense(), n.getWeightAfterDispense()) == 0
                        && Float.compare(o.getWeightBeforeFeed(), n.getWeightBeforeFeed()) == 0
                        && Float.compare(o.getTargetWeight(), n.getTargetWeight()) == 0
                        && o.isManualFeed() == n.isManualFeed()
                        && o.isMeasuredAfterTrayClose() == n.isMeasuredAfterTrayClose()
                        && o.isBowlAlreadyFull() == n.isBowlAlreadyFull()
                        && o.isConsumptionRecorded() == n.isConsumptionRecorded()
                        && o.isConsumptionPending() == n.isConsumptionPending()
                        && o.isAnomalyFlag() == n.isAnomalyFlag();
            }
        });

        this.records = newList;
        diff.dispatchUpdatesTo(this);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_feeding_record, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(records.get(position));
    }

    @Override
    public int getItemCount() {
        return records.size();
    }

    class ViewHolder extends RecyclerView.ViewHolder {

        private final CardView cardView;
        private final TextView tvScheduledTime;
        private final TextView tvActualTime;
        private final TextView tvTargetWeight;
        private final TextView tvDispensedWeight;
        private final TextView tvDeviation;
        private final TextView tvStatusBadge;
        private final View     statusAccent;

        ViewHolder(View itemView) {
            super(itemView);
            cardView         = itemView.findViewById(R.id.cardFeedingRecord);
            tvScheduledTime  = itemView.findViewById(R.id.tvScheduledTime);
            tvActualTime     = itemView.findViewById(R.id.tvActualTime);
            tvTargetWeight   = itemView.findViewById(R.id.tvTargetWeight);
            tvDispensedWeight= itemView.findViewById(R.id.tvDispensedWeight);
            tvDeviation      = itemView.findViewById(R.id.tvDeviation);
            tvStatusBadge    = itemView.findViewById(R.id.tvStatusBadge);
            statusAccent     = itemView.findViewById(R.id.statusAccent);

            itemView.setOnClickListener(v -> {
                int pos = getAdapterPosition();
                if (pos != RecyclerView.NO_POSITION && itemClickListener != null) {
                    itemClickListener.onItemClick(records.get(pos));
                }
            });
        }

        /** Dispensed / Consumed / Remaining lines, identical for manual and scheduled feeds. */
        private String buildBreakdown(FeedingRecord r) {
            boolean waiting = r.isAwaitingFeederData() && r.isInProgress(System.currentTimeMillis());
            String dispensed;
            if (r.isBowlAlreadyFull()) {
                dispensed = "Dispensed: 0.0g (bowl already full)";
            } else if (r.isDispensedKnown()) {
                dispensed = String.format(Locale.US, "Dispensed: %.1fg  (bowl %.1fg to %.1fg)",
                        r.getDispensedWeight(), r.getWeightBeforeFeed(), r.getWeightAfterDispense());
            } else {
                dispensed = waiting ? "Dispensed: waiting for feeder data" : "Dispensed: no data from feeder";
            }

            String consumed;
            String remaining;
            if (!r.isDispensedKnown()) {
                consumed = waiting ? "Consumed: pending, measured when the tray closes" : "Consumed: --";
                remaining = waiting ? "Remaining: pending, measured when the tray closes" : "Remaining: --";
            } else if (r.isConsumptionRecorded()) {
                consumed = String.format(Locale.US, "Consumed: %.1fg%s", r.getDisplayConsumedWeight(),
                        r.isMeasuredAfterTrayClose() ? "  (measured after the tray closed)" : "");
                remaining = String.format(Locale.US, "Remaining: %.1fg", r.getRemainingWeight());
            } else {
                consumed = "Consumed: pending, measured when the tray closes";
                remaining = "Remaining: pending, measured when the tray closes";
            }
            return dispensed + "\n" + consumed + "\n" + remaining;
        }

        void bind(FeedingRecord record) {
            String sched = record.getScheduledTimeDisplay() != null
                    ? record.getScheduledTimeDisplay() : "--:--";
            String actual = record.getActualTimeDisplay() != null
                    ? record.getActualTimeDisplay() : "--:--";

            boolean isMissed = record.getStatusEnum() == FeedingRecord.Status.MISSED;

            // Same layout for manual and scheduled feeds.
            tvScheduledTime.setText(record.isManualFeed() ? "Manual feed" : "Scheduled: " + sched);
            tvActualTime.setText("Actual: " + (isMissed ? "--:--" : actual));
            tvTargetWeight.setText(String.format(Locale.US, "Target: %.1fg", record.getDisplayTarget()));

            if (isMissed) {
                tvDispensedWeight.setText("Dispensed: --\nConsumed: --\nRemaining: --");
                tvDeviation.setVisibility(View.GONE);
            } else {
                tvDispensedWeight.setText(buildBreakdown(record));

                double deviation = record.getDeviationPercentage();
                tvDeviation.setVisibility(View.VISIBLE);
                if (Math.abs(deviation) > 1.0) {
                    tvDeviation.setText(String.format(Locale.US, "Deviation: %+.1f%%", deviation));
                    tvDeviation.setTextColor(ContextCompat.getColor(itemView.getContext(),
                            deviation > 0 ? R.color.colorDeviationHigh : R.color.colorDeviationLow));
                } else {
                    tvDeviation.setText("Deviation: Normal");
                    tvDeviation.setTextColor(ContextCompat.getColor(
                            itemView.getContext(), R.color.colorTextSecondary));
                }
            }

            switch (record.getStatusEnum()) {
                case COMPLETED:
                    boolean inProgress = record.isInProgress(System.currentTimeMillis());
                    boolean noData = record.isAwaitingFeederData() && !inProgress;
                    boundProgress.put(record.getFeedingId(), inProgress);
                    int badgeColor = (inProgress || noData) ? R.color.colorStatusWarning : R.color.colorStatusCompleted;
                    tvStatusBadge.setText(inProgress ? "IN PROGRESS" : (noData ? "NO DATA" : "COMPLETED"));
                    tvStatusBadge.setBackgroundResource((inProgress || noData)
                            ? R.drawable.badge_warning : R.drawable.badge_completed);
                    tvStatusBadge.setTextColor(ContextCompat.getColor(
                            itemView.getContext(), badgeColor));
                    cardView.setCardBackgroundColor(ContextCompat.getColor(
                            itemView.getContext(), R.color.colorCardNormal));
                    if (statusAccent != null)
                        statusAccent.setBackgroundColor(ContextCompat.getColor(
                                itemView.getContext(), badgeColor));
                    break;

                case MISSED:
                    tvStatusBadge.setText("MISSED");
                    tvStatusBadge.setBackgroundResource(R.drawable.badge_missed);
                    tvStatusBadge.setTextColor(ContextCompat.getColor(
                            itemView.getContext(), R.color.colorStatusMissed));
                    cardView.setCardBackgroundColor(ContextCompat.getColor(
                            itemView.getContext(), R.color.colorCardWarning));
                    if (statusAccent != null)
                        statusAccent.setBackgroundColor(ContextCompat.getColor(
                                itemView.getContext(), R.color.colorStatusMissed));
                    break;

                case ANOMALY:
                    tvStatusBadge.setText("ANOMALY");
                    tvStatusBadge.setBackgroundResource(R.drawable.badge_anomaly);
                    tvStatusBadge.setTextColor(ContextCompat.getColor(
                            itemView.getContext(), R.color.colorStatusAnomaly));
                    cardView.setCardBackgroundColor(ContextCompat.getColor(
                            itemView.getContext(), R.color.colorCardAlert));
                    if (statusAccent != null)
                        statusAccent.setBackgroundColor(ContextCompat.getColor(
                                itemView.getContext(), R.color.colorStatusAnomaly));
                    break;
            }
        }
    }
}
