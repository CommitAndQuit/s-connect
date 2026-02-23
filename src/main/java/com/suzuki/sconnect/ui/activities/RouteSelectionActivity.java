package com.suzuki.sconnect.ui.activities;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;

import com.mappls.sdk.geojson.Point;
import com.mappls.sdk.maps.MapplsMap;
import com.mappls.sdk.maps.MapView;
import com.mappls.sdk.maps.OnMapReadyCallback;
import com.mappls.sdk.maps.annotations.Polyline;
import com.mappls.sdk.maps.annotations.PolylineOptions;
import com.mappls.sdk.maps.camera.CameraUpdateFactory;
import com.mappls.sdk.maps.geometry.LatLngBounds;
import com.mappls.sdk.services.api.directions.models.DirectionsResponse;
import com.mappls.sdk.services.api.directions.models.DirectionsRoute;
import com.suzuki.sconnect.R;
import com.suzuki.sconnect.utils.MapplsRouteManager;

import java.util.ArrayList;
import java.util.List;

public class RouteSelectionActivity extends AppCompatActivity implements OnMapReadyCallback {

    private MapView mapView;
    private MapplsMap mapplsMap;
    private LinearLayout llRouteCards;
    private Button btnStartNav;

    private DirectionsResponse directionsResponse;
    private List<DirectionsRoute> routes;
    private List<Polyline> polylines = new ArrayList<>();
    private int selectedRouteIndex = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_route_selection);

        mapView = findViewById(R.id.route_map_view);
        llRouteCards = findViewById(R.id.ll_route_cards);
        btnStartNav = findViewById(R.id.btn_start_navigation);

        mapView.onCreate(savedInstanceState);
        mapView.getMapAsync(this);

        // In a real app, this would be passed via Intent or a ViewModel
        double destLat = getIntent().getDoubleExtra("dest_lat", 0);
        double destLng = getIntent().getDoubleExtra("dest_lng", 0);
        double originLat = getIntent().getDoubleExtra("origin_lat", 28.6139);
        double originLng = getIntent().getDoubleExtra("origin_lng", 77.2090);

        if (destLat != 0 && destLng != 0) {
            fetchRoutes(originLat, originLng, destLat, destLng);
        } else {
            Toast.makeText(this, "No destination specified", Toast.LENGTH_SHORT).show();
            finish();
        }

        btnStartNav.setOnClickListener(v -> {
            if (routes != null && !routes.isEmpty()) {
                Intent intent = new Intent(this, NavigationActivity.class);
                intent.putExtra("selectedRouteIndex", selectedRouteIndex);
                intent.putExtra("dest_lat", destLat);
                intent.putExtra("dest_lng", destLng);
                intent.putExtra("origin_lat", originLat);
                intent.putExtra("origin_lng", originLng);
                startActivity(intent);
                finish();
            }
        });
    }

    private void fetchRoutes(double originLat, double originLng, double destLat, double destLng) {
        findViewById(R.id.route_progress_bar).setVisibility(View.VISIBLE);
        MapplsRouteManager routeManager = new MapplsRouteManager();

        Point origin = Point.fromLngLat(originLng, originLat);
        Point destination = Point.fromLngLat(destLng, destLat);

        routeManager.fetchRoute(origin, destination, new MapplsRouteManager.RouteCallback() {
            @Override
            public void onRouteSuccess(DirectionsResponse response) {
                runOnUiThread(() -> {
                    findViewById(R.id.route_progress_bar).setVisibility(View.GONE);
                    directionsResponse = response;
                    routes = response.routes();
                    displayRoutes();
                });
            }

            @Override
            public void onRouteStringError(String error) {
                runOnUiThread(() -> {
                    findViewById(R.id.route_progress_bar).setVisibility(View.GONE);
                    Toast.makeText(RouteSelectionActivity.this, "Error: " + error, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    @Override
    public void onMapReady(@NonNull MapplsMap mapplsMap) {
        this.mapplsMap = mapplsMap;
        if (routes != null)
            displayRoutes();
    }

    @Override
    public void onMapError(int i, String s) {
        android.util.Log.e("RouteSelection", "Map Error: " + i + " - " + s);
    }

    private void displayRoutes() {
        if (mapplsMap == null || routes == null || routes.isEmpty())
            return;

        mapplsMap.clear();
        polylines.clear();
        llRouteCards.removeAllViews();

        for (int i = 0; i < routes.size(); i++) {
            DirectionsRoute route = routes.get(i);
            addRouteToMap(route, i == selectedRouteIndex);
            addRouteCard(route, i);
        }

        zoomToFitRoutes();
    }

    private void addRouteToMap(DirectionsRoute route, boolean isSelected) {
        // This is a simplification. Real implementation would decode geometry.
        // For now, we assume the polyline logic exists or we use the points.
        // Note: DirectionsRoute.geometry() is usually a polyline string.
        // PolylineUtils.decode() from mappls-geojson can extract points.

        List<com.mappls.sdk.geojson.Point> points = com.mappls.sdk.geojson.utils.PolylineUtils.decode(route.geometry(),
                6);
        List<com.mappls.sdk.maps.geometry.LatLng> latLngs = new ArrayList<>();
        for (Point p : points) {
            latLngs.add(new com.mappls.sdk.maps.geometry.LatLng(p.latitude(), p.longitude()));
        }

        PolylineOptions options = new PolylineOptions()
                .addAll(latLngs)
                .color(isSelected ? Color.BLUE : Color.GRAY)
                .width(isSelected ? 8 : 4);

        polylines.add(mapplsMap.addPolyline(options));
    }

    private void addRouteCard(DirectionsRoute route, int index) {
        View cardView = LayoutInflater.from(this).inflate(R.layout.item_route_card, llRouteCards, false);

        TextView tvDuration = cardView.findViewById(R.id.tv_route_duration);
        TextView tvDistance = cardView.findViewById(R.id.tv_route_distance);
        TextView tvVia = cardView.findViewById(R.id.tv_route_via);
        CardView root = cardView.findViewById(R.id.card_root);

        double durationMin = (route.duration() != null ? route.duration() : 0) / 60.0;
        double distanceKm = (route.distance() != null ? route.distance() : 0) / 1000.0;

        tvDuration.setText(String.format("%.0f min", durationMin));
        tvDistance.setText(String.format("%.1f km", distanceKm));

        if (route.legs() != null && !route.legs().isEmpty() && route.legs().get(0).summary() != null) {
            tvVia.setText("via " + route.legs().get(0).summary());
        } else {
            tvVia.setText("via Main Road");
        }

        if (index == selectedRouteIndex) {
            root.setCardBackgroundColor(Color.parseColor("#3D5AFE")); // Highlight color
        }

        cardView.setOnClickListener(v -> {
            selectedRouteIndex = index;
            displayRoutes();
        });

        llRouteCards.addView(cardView);
    }

    private void zoomToFitRoutes() {
        if (routes == null || routes.isEmpty())
            return;

        LatLngBounds.Builder builder = new LatLngBounds.Builder();
        for (DirectionsRoute route : routes) {
            List<Point> points = com.mappls.sdk.geojson.utils.PolylineUtils.decode(route.geometry(), 6);
            for (Point p : points) {
                builder.include(new com.mappls.sdk.maps.geometry.LatLng(p.latitude(), p.longitude()));
            }
        }

        mapplsMap.animateCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), 100));
    }

    @Override
    protected void onStart() {
        super.onStart();
        mapView.onStart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mapView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mapView.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        mapView.onStop();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        mapView.onSaveInstanceState(outState);
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        mapView.onLowMemory();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mapView.onDestroy();
    }
}
