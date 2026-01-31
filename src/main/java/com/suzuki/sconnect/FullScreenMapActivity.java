package com.suzuki.sconnect;

import android.app.ActivityOptions;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.mappls.sdk.maps.MapView;
import com.mappls.sdk.maps.MapplsMap;
import com.mappls.sdk.maps.OnMapReadyCallback;
import com.mappls.sdk.maps.Style;
import com.mappls.sdk.maps.annotations.MarkerOptions;
import com.mappls.sdk.maps.camera.CameraPosition;
import com.mappls.sdk.maps.camera.CameraUpdateFactory;
import com.mappls.sdk.maps.geometry.LatLng;
import com.mappls.sdk.maps.location.LocationComponent;
import com.mappls.sdk.maps.location.LocationComponentActivationOptions;
import com.mappls.sdk.maps.location.modes.CameraMode;
import com.mappls.sdk.maps.location.modes.RenderMode;
import com.mappls.sdk.maps.location.permissions.PermissionsManager;
import com.mappls.sdk.services.api.OnResponseCallback;
import com.mappls.sdk.services.api.autosuggest.MapplsAutoSuggest;
import com.mappls.sdk.services.api.autosuggest.MapplsAutosuggestManager;
import com.mappls.sdk.services.api.autosuggest.model.AutoSuggestAtlasResponse;
import com.mappls.sdk.services.api.autosuggest.model.ELocation;
import com.mappls.sdk.services.api.directions.DirectionsCriteria;
import com.mappls.sdk.services.api.directions.MapplsDirectionManager;
import com.mappls.sdk.services.api.directions.MapplsDirections;
import com.mappls.sdk.services.api.directions.models.DirectionsResponse;
import com.mappls.sdk.services.api.directions.models.DirectionsRoute;
import com.mappls.sdk.geojson.Point;
import com.mappls.sdk.geojson.LineString;
import com.mappls.sdk.geojson.Feature;
import com.mappls.sdk.geojson.FeatureCollection;
import com.mappls.sdk.maps.style.sources.GeoJsonSource;
import com.mappls.sdk.maps.style.layers.LineLayer;
import com.mappls.sdk.maps.style.layers.PropertyFactory;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import android.app.AlertDialog;
import android.graphics.Color;
import android.widget.TextView;
import android.widget.Button;

import java.util.ArrayList;
import java.util.List;

public class FullScreenMapActivity extends AppCompatActivity implements OnMapReadyCallback {

    private MapView mapView;
    private MapplsMap mapplsMap;
    private EditText etSearch;
    private ImageButton btnBack, btnClearSearch;
    private CardView searchResultsCard;
    private RecyclerView rvSearchResults;
    private FloatingActionButton fabCenterLocation;
    private SearchResultAdapter searchResultAdapter;

    // Navigation fields
    private DirectionsRoute currentRoute;
    private BottomSheetDialog navigationBottomSheet;
    private LatLng destinationMarker;
    private static final String ROUTE_SOURCE_ID = "route-source";
    private static final String ROUTE_LAYER_ID = "route-layer";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Enable shared element transitions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setSharedElementEnterTransition(new android.transition.ChangeBounds());
            getWindow().setSharedElementExitTransition(new android.transition.ChangeBounds());
        }

        setContentView(R.layout.activity_fullscreen_map);

        initializeViews(savedInstanceState);
        setupSearch();
    }

    private void initializeViews(Bundle savedInstanceState) {
        mapView = findViewById(R.id.map_view);
        etSearch = findViewById(R.id.etSearch);
        btnBack = findViewById(R.id.btnBack);
        btnClearSearch = findViewById(R.id.btnClearSearch);
        searchResultsCard = findViewById(R.id.searchResultsCard);
        rvSearchResults = findViewById(R.id.rvSearchResults);
        fabCenterLocation = findViewById(R.id.fabCenterLocation);

        if (savedInstanceState != null) {
            mapView.onCreate(savedInstanceState);
        } else {
            mapView.onCreate(null);
        }
        mapView.getMapAsync(this);

        btnBack.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                finishAfterTransition();
            } else {
                finish();
            }
        });

        btnClearSearch.setOnClickListener(v -> {
            etSearch.setText("");
            searchResultsCard.setVisibility(View.GONE);
        });

        fabCenterLocation.setOnClickListener(v -> centerMapToUserLocation());

        // Setup RecyclerView
        searchResultAdapter = new SearchResultAdapter(this::onSearchResultClick);
        rvSearchResults.setLayoutManager(new LinearLayoutManager(this));
        rvSearchResults.setAdapter(searchResultAdapter);
    }

    @Override
    public void onMapReady(MapplsMap map) {
        this.mapplsMap = map;

        // Get camera position from intent (passed from MainActivity)
        double latitude = getIntent().getDoubleExtra("latitude", 28.6139); // Default to Delhi
        double longitude = getIntent().getDoubleExtra("longitude", 77.2090);
        double zoom = getIntent().getDoubleExtra("zoom", 13.0);

        LatLng initialPosition = new LatLng(latitude, longitude);
        map.setCameraPosition(new CameraPosition.Builder()
                .target(initialPosition)
                .zoom(zoom)
                .build());

        map.getStyle(new Style.OnStyleLoaded() {
            @Override
            public void onStyleLoaded(@NonNull Style style) {
                enableLocationComponent(style);

                // Initialize route source and layer for drawing routes
                initializeRouteLayer(style);
            }
        });

        // Add pin drop on long press
        map.addOnMapLongClickListener(new MapplsMap.OnMapLongClickListener() {
            @Override
            public boolean onMapLongClick(@NonNull LatLng point) {
                showPinDropDialog(point);
                return true;
            }
        });
    }

    @Override
    public void onMapError(int errorCode, String errorMessage) {
        // Handle map error
    }

    private void enableLocationComponent(Style style) {
        if (PermissionsManager.areLocationPermissionsGranted(this)) {
            LocationComponent locationComponent = mapplsMap.getLocationComponent();
            LocationComponentActivationOptions options = LocationComponentActivationOptions.builder(this, style)
                    .build();
            locationComponent.activateLocationComponent(options);
            locationComponent.setLocationComponentEnabled(true);
            locationComponent.setCameraMode(CameraMode.TRACKING);
            locationComponent.setRenderMode(RenderMode.COMPASS);
        }
    }

    private void setupSearch() {
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (s.length() > 0) {
                    btnClearSearch.setVisibility(View.VISIBLE);
                    performSearch(s.toString());
                } else {
                    btnClearSearch.setVisibility(View.GONE);
                    searchResultsCard.setVisibility(View.GONE);
                    searchResultAdapter.clear();
                }
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
    }

    private void performSearch(String query) {
        MapplsAutoSuggest autoSuggest = MapplsAutoSuggest.builder()
                .query(query)
                .build();

        MapplsAutosuggestManager.newInstance(autoSuggest).call(new OnResponseCallback<AutoSuggestAtlasResponse>() {
            @Override
            public void onSuccess(AutoSuggestAtlasResponse response) {
                if (response != null && response.getSuggestedLocations() != null) {
                    List<ELocation> suggestedSearches = response.getSuggestedLocations();
                    if (!suggestedSearches.isEmpty()) {
                        searchResultAdapter.setResults(suggestedSearches);
                        searchResultsCard.setVisibility(View.VISIBLE);
                    } else {
                        searchResultsCard.setVisibility(View.GONE);
                    }
                }
            }

            @Override
            public void onError(int errorCode, String error) {
                searchResultsCard.setVisibility(View.GONE);
            }
        });
    }

    private void onSearchResultClick(ELocation location) {
        if (mapplsMap != null && location != null && location.latitude != null && location.longitude != null) {
            LatLng latLng = new LatLng(location.latitude, location.longitude);

            // Hide search results
            searchResultsCard.setVisibility(View.GONE);
            etSearch.setText("");

            // Show dialog with navigation option
            showDestinationDialog(latLng, location.placeName);
            etSearch.clearFocus();
        }
    }

    private void centerMapToUserLocation() {
        if (mapplsMap != null) {
            LocationComponent locationComponent = mapplsMap.getLocationComponent();
            if (locationComponent != null && locationComponent.getLastKnownLocation() != null) {
                Location lastLocation = locationComponent.getLastKnownLocation();
                LatLng userLatLng = new LatLng(lastLocation.getLatitude(), lastLocation.getLongitude());
                mapplsMap.animateCamera(CameraUpdateFactory.newLatLngZoom(userLatLng, 15.0));
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        mapView.onStart();
    }

    @Override
    protected void onStop() {
        super.onStop();
        mapView.onStop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mapView.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        mapView.onSaveInstanceState(outState);
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        mapView.onLowMemory();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mapView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mapView.onResume();
    }

    // Navigation Methods

    private void showPinDropDialog(LatLng point) {
        new AlertDialog.Builder(this)
                .setTitle("Drop Pin")
                .setMessage("Navigate to this location?")
                .setPositiveButton("Navigate", (dialog, which) -> {
                    // Add marker at pin location
                    mapplsMap.clear();
                    mapplsMap.addMarker(new MarkerOptions()
                            .position(point)
                            .title("Dropped Pin"));

                    destinationMarker = point;
                    fetchDirections(point);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showDestinationDialog(LatLng destination, String placeName) {
        new AlertDialog.Builder(this)
                .setTitle(placeName)
                .setMessage("What would you like to do?")
                .setPositiveButton("Navigate", (dialog, which) -> {
                    // Navigate to this location
                    mapplsMap.clear();
                    mapplsMap.addMarker(new MarkerOptions()
                            .position(destination)
                            .title(placeName));

                    destinationMarker = destination;
                    fetchDirections(destination);
                })
                .setNeutralButton("Show on Map", (dialog, which) -> {
                    // Just show the location
                    mapplsMap.animateCamera(CameraUpdateFactory.newLatLngZoom(destination, 15.0));
                    mapplsMap.addMarker(new MarkerOptions()
                            .position(destination)
                            .title(placeName));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void fetchDirections(LatLng destination) {
        LocationComponent locationComponent = mapplsMap.getLocationComponent();
        if (locationComponent == null || !locationComponent.isLocationComponentEnabled()) {
            // Location unavailable
            return;
        }

        Location lastLocation = locationComponent.getLastKnownLocation();
        if (lastLocation == null) {
            return;
        }

        Point origin = Point.fromLngLat(lastLocation.getLongitude(), lastLocation.getLatitude());
        Point dest = Point.fromLngLat(destination.getLongitude(), destination.getLatitude());

        MapplsDirections directions = MapplsDirections.builder()
                .origin(origin)
                .destination(dest)
                .profile("biking") // 2-wheeler profile
                .resource(DirectionsCriteria.RESOURCE_ROUTE_ETA)
                .overview("full")
                .steps(true)
                .annotations(DirectionsCriteria.ANNOTATION_CONGESTION,
                        DirectionsCriteria.ANNOTATION_DURATION)
                .build();

        MapplsDirectionManager.newInstance(directions).call(new OnResponseCallback<DirectionsResponse>() {
            @Override
            public void onSuccess(DirectionsResponse response) {
                if (response != null && response.routes() != null && !response.routes().isEmpty()) {
                    currentRoute = response.routes().get(0);
                    displayRoute(currentRoute, new LatLng(lastLocation.getLatitude(), lastLocation.getLongitude()),
                            destination);
                    showNavigationBottomSheet(currentRoute);
                }
            }

            @Override
            public void onError(int errorCode, String error) {
                // Handle error - could show toast
            }
        });
    }

    private void displayRoute(DirectionsRoute route, LatLng origin, LatLng destination) {
        if (mapplsMap == null || route.geometry() == null) {
            return;
        }

        mapplsMap.getStyle(new Style.OnStyleLoaded() {
            @Override
            public void onStyleLoaded(@NonNull Style style) {
                GeoJsonSource source = style.getSourceAs(ROUTE_SOURCE_ID);
                if (source == null) {
                    return;
                }

                // Create LineString from route geometry
                LineString lineString = LineString.fromPolyline(route.geometry(), 6);
                Feature feature = Feature.fromGeometry(lineString);
                FeatureCollection featureCollection = FeatureCollection.fromFeature(feature);

                // Update the source
                source.setGeoJson(featureCollection);

                // Add start and end markers
                mapplsMap.addMarker(new MarkerOptions()
                        .position(origin)
                        .title("Start"));

                mapplsMap.addMarker(new MarkerOptions()
                        .position(destination)
                        .title("Destination"));

                // Adjust camera to show entire route
                mapplsMap.animateCamera(CameraUpdateFactory.newLatLngZoom(
                        new LatLng((origin.getLatitude() + destination.getLatitude()) / 2,
                                (origin.getLongitude() + destination.getLongitude()) / 2),
                        12.0));
            }
        });
    }

    private void showNavigationBottomSheet(DirectionsRoute route) {
        View sheetView = getLayoutInflater().inflate(R.layout.navigation_bottom_sheet, null);
        navigationBottomSheet = new BottomSheetDialog(this);
        navigationBottomSheet.setContentView(sheetView);

        TextView tvDistance = sheetView.findViewById(R.id.tvDistance);
        TextView tvEta = sheetView.findViewById(R.id.tvEta);
        Button btnStartNavigation = sheetView.findViewById(R.id.btnStartNavigation);
        Button btnCancel = sheetView.findViewById(R.id.btnCancelNavigation);

        // Format distance (meters to km)
        double distanceKm = route.distance() / 1000.0;
        tvDistance.setText(String.format("%.1f km", distanceKm));

        // Format duration (seconds to minutes)
        int durationMinutes = (int) (route.duration() / 60.0);
        tvEta.setText(String.format("%d min", durationMinutes));

        btnStartNavigation.setOnClickListener(v -> {
            // TODO: Start turn-by-turn navigation if needed
            navigationBottomSheet.dismiss();
        });

        btnCancel.setOnClickListener(v -> {
            clearRoute();
            navigationBottomSheet.dismiss();
        });

        navigationBottomSheet.show();
    }

    private void initializeRouteLayer(Style style) {
        // Add a GeoJSON source for the route
        GeoJsonSource routeSource = new GeoJsonSource(ROUTE_SOURCE_ID);
        style.addSource(routeSource);

        // Add a line layer for drawing the route
        LineLayer routeLayer = new LineLayer(ROUTE_LAYER_ID, ROUTE_SOURCE_ID);
        routeLayer.setProperties(
                PropertyFactory.lineColor(Color.parseColor("#3b7dd6")),
                PropertyFactory.lineWidth(5f),
                PropertyFactory.lineCap("round"),
                PropertyFactory.lineJoin("round"));
        style.addLayer(routeLayer);
    }

    private void clearRoute() {
        if (mapplsMap == null) {
            return;
        }

        mapplsMap.getStyle(new Style.OnStyleLoaded() {
            @Override
            public void onStyleLoaded(@NonNull Style style) {
                GeoJsonSource source = style.getSourceAs(ROUTE_SOURCE_ID);
                if (source != null) {
                    source.setGeoJson(FeatureCollection.fromFeatures(new ArrayList<>()));
                }
            }
        });

        currentRoute = null;
        mapplsMap.clear();
    }
}
