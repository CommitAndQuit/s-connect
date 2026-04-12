package com.suzuki.sconnect.ui.adapters;

import android.content.Intent;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.suzuki.sconnect.R;
import com.suzuki.sconnect.data.model.ServiceReminder;
import com.suzuki.sconnect.ui.activities.EditServiceReminderActivity;

import java.util.List;

public class ServiceReminderAdapter extends RecyclerView.Adapter<ServiceReminderAdapter.ViewHolder> {

    private List<ServiceReminder> reminders;
    private int currentOdometer;

    public ServiceReminderAdapter(List<ServiceReminder> reminders, int currentOdometer) {
        this.reminders = reminders;
        this.currentOdometer = currentOdometer;
    }

    public void updateData(List<ServiceReminder> reminders, int currentOdometer) {
        this.reminders = reminders;
        this.currentOdometer = currentOdometer;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_service_reminder, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ServiceReminder reminder = reminders.get(position);
        holder.tvName.setText(reminder.getName());

        long now = System.currentTimeMillis();
        long nextDate = reminder.getNextServiceDate();
        int nextOdo = reminder.getNextServiceOdometer();

        int daysRemaining = (int) ((nextDate - now) / (24 * 60 * 60 * 1000));
        int kmRemaining = nextOdo - currentOdometer;

        StringBuilder status = new StringBuilder();
        boolean isDue = false;

        if (reminder.getIntervalKm() > 0) {
            if (kmRemaining <= 0) {
                status.append("Kilometers due");
                isDue = true;
            } else {
                status.append("Due in ").append(kmRemaining).append(" km");
            }
        }

        if (reminder.getIntervalDays() > 0) {
            if (status.length() > 0) status.append(" or ");
            if (daysRemaining <= 0) {
                status.append("Time due");
                isDue = true;
            } else {
                status.append(daysRemaining).append(" days");
            }
        }

        holder.tvStatus.setText(status.toString());
        holder.tvStatus.setTextColor(isDue ? Color.parseColor("#EF4444") : Color.parseColor("#9CA3AF"));

        // Calculate progress (0-100)
        int progress = 0;
        if (reminder.getIntervalKm() > 0) {
            float kmProgress = (float) (currentOdometer - reminder.getLastServiceOdometer()) / reminder.getIntervalKm() * 100;
            progress = Math.max(progress, (int) Math.min(100, kmProgress));
        }
        if (reminder.getIntervalDays() > 0) {
            float timeProgress = (float) (now - reminder.getLastServiceDate()) / (reminder.getNextServiceDate() - reminder.getLastServiceDate()) * 100;
            progress = Math.max(progress, (int) Math.min(100, timeProgress));
        }

        holder.progressBar.setProgress(progress);
        holder.progressBar.setProgressTintList(android.content.res.ColorStateList.valueOf(
                isDue ? Color.parseColor("#EF4444") : Color.parseColor("#6C63FF")));

        holder.btnEdit.setOnClickListener(v -> {
            Intent intent = new Intent(v.getContext(), EditServiceReminderActivity.class);
            intent.putExtra("reminder_id", reminder.getId());
            v.getContext().startActivity(intent);
        });
    }

    @Override
    public int getItemCount() {
        return reminders.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvName, tvStatus;
        ImageView ivIcon;
        ProgressBar progressBar;
        ImageButton btnEdit;

        ViewHolder(View itemView) {
            super(itemView);
            tvName = itemView.findViewById(R.id.tvServiceName);
            tvStatus = itemView.findViewById(R.id.tvServiceStatus);
            ivIcon = itemView.findViewById(R.id.ivServiceIcon);
            progressBar = itemView.findViewById(R.id.pbServiceProgress);
            btnEdit = itemView.findViewById(R.id.btnEditService);
        }
    }
}
