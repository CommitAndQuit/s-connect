package com.suzuki.sconnect.ui.adapters;

import android.content.Context;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.suzuki.sconnect.R;
import com.suzuki.sconnect.data.model.TripRecord;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * P2 — RecyclerView adapter for the trip history list.
 * Binds {@link TripRecord} objects to {@code item_trip_card.xml}.
 */
public class TripAdapter extends RecyclerView.Adapter<TripAdapter.TripViewHolder> {

    public interface OnTripClickListener {
        void onTripClick(TripRecord trip);
    }

    private final List<TripRecord> trips;
    private final OnTripClickListener listener;
    private static final SimpleDateFormat DATE_FORMAT =
            new SimpleDateFormat("EEE, d MMM yyyy · h:mm a", Locale.getDefault());

    public TripAdapter(List<TripRecord> trips, OnTripClickListener listener) {
        this.trips = trips != null ? trips : new ArrayList<>();
        this.listener = listener;
    }

    @NonNull
    @Override
    public TripViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_trip_card, parent, false);
        return new TripViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull TripViewHolder holder, int position) {
        TripRecord trip = trips.get(position);
        holder.bind(trip, listener);
    }

    @Override
    public int getItemCount() {
        return trips.size();
    }

    public void updateTrips(List<TripRecord> newTrips) {
        trips.clear();
        if (newTrips != null) trips.addAll(newTrips);
        notifyDataSetChanged();
    }

    static class TripViewHolder extends RecyclerView.ViewHolder {
        private final TextView tvDate;
        private final TextView tvDuration;
        private final TextView tvStart;
        private final TextView tvEnd;
        private final TextView tvDistance;
        private final TextView tvTopSpeed;
        private final TextView tvMileage;

        TripViewHolder(@NonNull View itemView) {
            super(itemView);
            tvDate = itemView.findViewById(R.id.tv_trip_date);
            tvDuration = itemView.findViewById(R.id.tv_trip_duration);
            tvStart = itemView.findViewById(R.id.tv_trip_start);
            tvEnd = itemView.findViewById(R.id.tv_trip_end);
            tvDistance = itemView.findViewById(R.id.tv_trip_distance);
            tvTopSpeed = itemView.findViewById(R.id.tv_trip_top_speed);
            tvMileage = itemView.findViewById(R.id.tv_trip_mileage);
        }

        void bind(TripRecord trip, OnTripClickListener listener) {
            // Date
            tvDate.setText(DATE_FORMAT.format(new Date(trip.getStartTimeMs())));

            // Duration
            long durationMs = trip.getDurationMs();
            if (durationMs > 0) {
                long mins = TimeUnit.MILLISECONDS.toMinutes(durationMs);
                long hrs = mins / 60;
                long remainMins = mins % 60;
                tvDuration.setText(hrs > 0
                        ? String.format(Locale.getDefault(), "%dh %dm", hrs, remainMins)
                        : String.format(Locale.getDefault(), "%d min", mins));
            } else {
                tvDuration.setText("In Progress");
            }

            // Route names
            tvStart.setText(trip.getStartPlaceName() != null ? trip.getStartPlaceName() : "Start");
            tvEnd.setText(trip.getEndPlaceName() != null ? trip.getEndPlaceName() : "Destination");

            // Stats
            tvDistance.setText(String.format(Locale.getDefault(), "%.1f km", trip.getDistanceKm()));
            tvTopSpeed.setText(String.format(Locale.getDefault(), "%d km/h", trip.getTopSpeedKmh()));

            float mileage = trip.getMileageKmL();
            tvMileage.setText(mileage > 0
                    ? String.format(Locale.getDefault(), "%.1f km/L", mileage)
                    : "—");

            itemView.setOnClickListener(v -> {
                if (listener != null) listener.onTripClick(trip);
            });
        }
    }
}
