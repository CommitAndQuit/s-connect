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
            mapplsMap.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15.0));

            // Clear existing markers and add new one
            mapplsMap.clear();
            mapplsMap.addMarker(new MarkerOptions()
                    .position(latLng)
                    .title(location.placeName));

            // Hide search results and clear search
            searchResultsCard.setVisibility(View.GONE);
            etSearch.setText(location.placeName);
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
}
