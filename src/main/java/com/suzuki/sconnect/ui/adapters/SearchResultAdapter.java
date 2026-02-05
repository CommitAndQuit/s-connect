package com.suzuki.sconnect.ui.adapters;

import com.suzuki.sconnect.R;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.mappls.sdk.services.api.autosuggest.model.ELocation;

import java.util.ArrayList;
import java.util.List;

public class SearchResultAdapter extends RecyclerView.Adapter<SearchResultAdapter.ViewHolder> {

    private List<ELocation> results = new ArrayList<>();
    private OnResultClickListener listener;

    public interface OnResultClickListener {
        void onResultClick(ELocation location);
    }

    public SearchResultAdapter(OnResultClickListener listener) {
        this.listener = listener;
    }

    public void setResults(List<ELocation> results) {
        this.results = results != null ? results : new ArrayList<>();
        notifyDataSetChanged();
    }

    public void clear() {
        this.results.clear();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.search_result_item, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ELocation location = results.get(position);
        holder.bind(location, listener);
    }

    @Override
    public int getItemCount() {
        return results.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvPlaceName, tvPlaceAddress;

        ViewHolder(View itemView) {
            super(itemView);
            tvPlaceName = itemView.findViewById(R.id.tvPlaceName);
            tvPlaceAddress = itemView.findViewById(R.id.tvPlaceAddress);
        }

        void bind(ELocation location, OnResultClickListener listener) {
            tvPlaceName.setText(location.placeName != null ? location.placeName : "Unknown Place");
            tvPlaceAddress.setText(location.placeAddress != null ? location.placeAddress : "");

            itemView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onResultClick(location);
                }
            });
        }
    }
}
