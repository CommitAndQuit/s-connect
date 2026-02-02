package com.suzuki.sconnect;

import android.app.ActivityOptions;
import android.content.Intent;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Toast;
import android.content.SharedPreferences;

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
    private FloatingActionButton fabCenterLocation, fabHome;
    private SearchResultAdapter searchResultAdapter;

    private static final String PREFS_NAME = "SConnectPrefs";
    private static final String KEY_HOME_LAT = "home_lat";
    private static final String KEY_HOME_LNG = "home_lng";

    // Navigation fields

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
        fabHome = findViewById(R.id.fabHome);

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

        fabHome.setOnClickListener(v -> handleHomeButtonClick());
        fabHome.setOnLongClickListener(v -> {
            resetHomeLocation();
            return true;
        });

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
                    startNavigation(point);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showDestinationDialog(LatLng destination, String placeName) {
        new AlertDialog.Builder(this)
                .setTitle(placeName)
                .setMessage("What would you like to do?")
                .setPositiveButton("Navigate", (dialog, which) -> {
                    startNavigation(destination);
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

    private void startNavigation(LatLng destination) {
        Intent intent = new Intent(this, NavigationActivity.class);
        LocationComponent locationComponent = mapplsMap.getLocationComponent();
        if (locationComponent != null && locationComponent.getLastKnownLocation() != null) {
            Location lastLocation = locationComponent.getLastKnownLocation();
            intent.putExtra("origin_lat", lastLocation.getLatitude());
            intent.putExtra("origin_lng", lastLocation.getLongitude());
        }
        intent.putExtra("dest_lat", destination.getLatitude());
        intent.putExtra("dest_lng", destination.getLongitude());
        startActivity(intent);
    }

    private void handleHomeButtonClick() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        float homeLat = prefs.getFloat(KEY_HOME_LAT, 0);
        float homeLng = prefs.getFloat(KEY_HOME_LNG, 0);

        if (homeLat == 0 || homeLng == 0) {
            // Home not saved, prompt to save current map center
            if (mapplsMap != null) {
                LatLng center = mapplsMap.getCameraPosition().target;
                new AlertDialog.Builder(this)
                        .setTitle("Save Home")
                        .setMessage("Should I save the current map center as your Home location?")
                        .setPositiveButton("Save", (dialog, which) -> {
                            prefs.edit()
                                    .putFloat(KEY_HOME_LAT, (float) center.getLatitude())
                                    .putFloat(KEY_HOME_LNG, (float) center.getLongitude())
                                    .apply();
                            Toast.makeText(this, "Home location saved!", Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        } else {
            // Home saved, navigate to it
            startNavigation(new LatLng(homeLat, homeLng));
        }
    }

    private void resetHomeLocation() {
        new AlertDialog.Builder(this)
                .setTitle("Reset Home")
                .setMessage("Do you want to clear your saved Home location?")
                .setPositiveButton("Reset", (dialog, which) -> {
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                            .remove(KEY_HOME_LAT)
                            .remove(KEY_HOME_LNG)
                            .apply();
                    Toast.makeText(this, "Home location cleared", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

}
