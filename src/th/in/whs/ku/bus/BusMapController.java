package th.in.whs.ku.bus;

import java.util.ArrayList;
import java.util.HashMap;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import th.in.whs.ku.bus.api.Bus;
import th.in.whs.ku.bus.api.BusPosition;
import th.in.whs.ku.bus.api.BusStopList;
import th.in.whs.ku.bus.api.ListenerList;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.support.v7.app.ActionBar;
import android.util.Log;
import android.util.SparseArray;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.GoogleMap.OnInfoWindowClickListener;
import com.google.android.gms.maps.GoogleMap.OnMapClickListener;
import com.google.android.gms.maps.GoogleMap.OnMarkerClickListener;
import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.joshdholtz.sentry.Sentry;

/**
 * Bus map controller for using with Google Maps Android v2
 * Usage:
 * BusMapController controller = new BusMapController(this, map.getMap());
 * 
 * To draw bus:
 * controller.registerListener();
 * and in your onPause/onResume call the method of the same name on this class
 * and unregisterListener in your onDestroy to prevent timer leak
 */
public class BusMapController implements OnInfoWindowClickListener, OnMarkerClickListener, OnMapClickListener {
//	private static final float IN_PARK_ALPHA = 0.2f;
	private static final float OUT_OF_SERVICE_ALPHA = 0.6f;

	/**
	 * Helper method to get bus color
	 * @param lineid
	 * @return Color
	 */
	public static int getColor(int lineid){
    	switch(lineid){
		case 1:
			return R.color.line1;
		case 2:
			return R.color.line2;
		case 3:
			return R.color.line3;
		case 4:
			return R.color.line4;
		case 5:
			return R.color.line5;
		default:
			return R.color.line1;
		}
    }
	
	private GoogleMap map;
	private Context context;
	/**
	 * Array of bus markers
	 * Key is bus id
	 */
	private SparseArray<Marker> markers = new SparseArray<Marker>();
	private int listenerId = -1;
	private boolean useFilter = false;
	private ArrayList<Filter> filter = new ArrayList<Filter>();
	private Polyline polyline;
	private String currentPolyline = "0";
	/**
	 * Array of bus stop markers
	 */
	private ArrayList<StopMarker> stopMarker = new ArrayList<StopMarker>();
	/**
	 * How the polyline become visible
	 * 0: no (no call yet)
	 * 1: yes (direct call to drawPolyline)
	 * 2: no, but drawn by marker tap (onMarkerClick)
	 */
	private int polylineRequestedByUser = 0;
	private boolean allowBusStopClick = true;
	
	public BusMapController(Context context, GoogleMap map){
		this.context = context;
		this.map = map;
		if(map == null){
			throw new IllegalStateException("Map is not defined");
		}
		map.setOnInfoWindowClickListener(this);
		map.setOnMarkerClickListener(this);
		map.setOnMapClickListener(this);
	}
	
	/**
	 * Is the filter enabled?
	 * @param set true to enable filter, false otherwise
	 * @see addFilter
	 */
	public void setFilterActive(boolean set){
		this.useFilter = set;
		update(BusPosition.gets());
	}
	
	public boolean getFilterActive(){
		return this.useFilter;
	}
	
	/**
	 * Filter is used to show only information of interest 
	 * @param item
	 */
	public void addFilter(Filter item){
		if(filter.contains(item)){
			return;
		}
		filter.add(item);
		if(useFilter){
			update(BusPosition.gets());
		}
	}
	
	public boolean removeFilter(Filter item){
		boolean out = filter.remove(item);
		if(out && useFilter){
			update(BusPosition.gets());
		}
		return out;
	}
	
	public void clearFilter(){
		boolean update = filter.size() > 0;
		filter.clear();
		if(update && useFilter){
			update(BusPosition.gets());
		}
	}
	
	/**
	 * Redraw
	 * @param data Bus position data from BusPosition.gets()
	 */
	public void update(SparseArray<Bus> data){
		ArrayList<Integer> hasBus = new ArrayList<Integer>();
		for(int i=0; i < data.size(); i++){
			Bus bus = data.valueAt(i);
			if(bus.isinpark){
				continue;
			}
			if(useFilter && !isFiltered(bus)){
				continue;
			}
			hasBus.add(bus.id);
			
			LatLng latlng = new LatLng(bus.latitude, bus.longitude);
			BitmapDescriptor icon = getBusIcon(bus);
			Marker marker = markers.get(bus.id);
			
			if(marker == null){
				marker = map.addMarker(new MarkerOptions().position(latlng));
				markers.put(bus.id, marker);
			}
			marker.setPosition(latlng);
			if(bus.lineid == 0 || bus.isinpark){
				marker.setTitle(bus.name);
				if(bus.isinpark){
					marker.setSnippet(context.getString(R.string.bus_parking));
				}else{
					marker.setSnippet(context.getString(R.string.bus_no_line));
				}
			}else{
				marker.setTitle(String.format(context.getString(R.string.bus_line), bus.lineid));
				marker.setSnippet(bus.name);
			}
			marker.setIcon(icon);
//			if(bus.isinpark){
//				marker.setAlpha(IN_PARK_ALPHA);
//			}else 
			if(!bus.isinline){
				marker.setAlpha(OUT_OF_SERVICE_ALPHA);
			}else{
				marker.setAlpha(1f);
			}
		}
		ArrayList<Integer> remove = new ArrayList<Integer>();
		for(int i=0; i<markers.size(); i++){
			int key = markers.keyAt(i);
			if(!hasBus.contains(key)){
				markers.valueAt(i).remove();
				remove.add(markers.keyAt(i));
			}
		}
		// remove after otherwise .size() will break
		for(int removeKey : remove){
			markers.remove(removeKey);
		}
	}
	
	/**
	 * Checker for filter
	 */
	private boolean isFiltered(Bus bus) {
		for(Filter f : filter){
			if(f.type == Filter.FilterType.BUS && bus.id == f.value){
				return true;
			}else if(f.type == Filter.FilterType.LINE && bus.lineid == f.value){
				return true;
			}
		}
		return false;
	}

	public void drawPolyline(int lineId){
		drawPolyline(String.valueOf(lineId));
	}
	
	public void drawPolyline(String lineId, String from, String to){
		_drawPolyline(String.valueOf(lineId), from, to);
	}
	
	public void drawPolyline(String lineId){
		polylineRequestedByUser = 1;
		_drawPolyline(lineId);
	}
	
	private void _drawPolyline(String lineId){
		_drawPolyline(lineId, null, null);
	}
	
	private void _drawPolyline(String lineId, String fromStop, String toStop){
		if(lineId.equals(currentPolyline)){
			return;
		}
		currentPolyline = lineId;
		_clearPolyline();
		// somehow inlining this check still make race condition
		// http://sentry.whs.in.th/kusmartbus/android/group/107/
		JSONObject listRoot = BusStopList.data();
		if(listRoot == null){
			return;
		}
		int color = context.getResources().getColor(getColor(Integer.valueOf(lineId)));
		double[] startStop = new double[2];
		double[] endStop = new double[2];
		boolean foundStart = fromStop == null;
		boolean foundStop = toStop == null;
		// Draw stop marker
		try{
			JSONObject data = listRoot.getJSONObject("StopOrder");
			// or maybe /group/107/ could be from this line
			if(data == null){
				return;
			}
			JSONArray line = data.getJSONArray(lineId);
			JSONObject stopData = listRoot.getJSONObject("Stop");
			BitmapDescriptor icon = getStopIcon(lineId);
			
			stopMarker.ensureCapacity(line.length());
			
			for(int loop=0; loop<2; loop++){
				for(int i=1; i<line.length(); i++){
					JSONObject stop = stopData.getJSONObject(line.getString(i));
					
					if(!foundStart){
						if(stop.getString("ID").equals(fromStop)){
							foundStart = true;
							startStop[0] = stop.getDouble("Latitude");
							startStop[1] = stop.getDouble("Longitude");
						}else{
							continue;
						}
					}
					
					MarkerOptions marker = new MarkerOptions();
					marker.position(
							new LatLng(
									stop.getDouble("Latitude"),
									stop.getDouble("Longitude")
							)
					).title(stop.getString("Name"))
						.anchor(0.5f, 0.5f)
						.icon(icon);
					
					stopMarker.add(new StopMarker(map.addMarker(marker), stop));
					
					if(toStop != null && stop.getString("ID").equals(toStop)){
						endStop[0] = stop.getDouble("Latitude");
						endStop[1] = stop.getDouble("Longitude");
						foundStop = true;
						break;
					}
				}
				// we traced the entire line and
				// still doesn't find the start stop
				if(!foundStart){
					return;
				}
				// if we want to draw single loop
				// then don't wrap around
				if(foundStop){
					break;
				}
			}
		}catch(JSONException e){
		}
		// Draw bus marker
		foundStart = true;
		foundStop = true;
		try {
			JSONObject data = listRoot.getJSONObject("Polyline");
			if(data == null){
				return;
			}
			JSONArray line = data.getJSONArray(lineId);
			if(line == null){
				return;
			}
			
			float[] lastItem = toFloat(line.getString(0).split(","));
			PolylineOptions pol = new PolylineOptions().width(5).color(color);
			
			for(int loop=0; loop<2; loop++){
				for(int i=1; i<line.length(); i++){
					String[] rawItem = line.getString(i).split(",");
					float[] item = toFloat(rawItem);
					
					// This doesn't work. Polyline does not draw from stop to stop
					// but rather parallel.
					/*if(fromStop != null){
						if(item[0] == startStop[0] && item[1] == startStop[1]){
							foundStart = true;
						}
						if(!foundStart){
							continue;
						}
					}*/
					
					if(rawItem[0].equals("13.85004") && rawItem[1].equals("100.572433")){
						// https://code.google.com/p/gmaps-api-issues/issues/detail?id=5313
						item[0] += 0.000001;
					}
					
					pol.add(new LatLng(lastItem[0], lastItem[1]), new LatLng(item[0], item[1]));
					
					/*if(item[0] == endStop[0] && item[1] == endStop[1]){
						foundStop = true;
						break;
					}*/
					
					lastItem = item;
				}
				if(toStop == null || foundStop){
					break;
				}
			}
			
			polyline = map.addPolyline(pol);
		} catch (JSONException e) {
		}
	}
	
	/**
	 * Convert array of string of float number to array of float
	 * @param str String of float numbers
	 * @return Float array
	 */
	private float[] toFloat(String[] str){
		float[] out = new float[str.length];
		for (int i = 0; i < str.length; i++) {
			out[i] = Float.valueOf(str[i]);
		}
		return out;
	}
	
	/**
	 * Hide all polylines
	 */
	public void clearPolyline(){
		polylineRequestedByUser = 0;
		currentPolyline = "0";
		_clearPolyline();
	}
	
	private void _clearPolyline(){
		if(polyline == null){
			return;
		}
		polyline.remove();
		for(StopMarker mark : stopMarker){
			mark.marker.remove();
		}
		stopMarker.clear();
	}
	
	public Marker get(int id){
		return markers.get(id);
	}
	
	private SparseArray<BitmapDescriptor> busIconCache = new SparseArray<BitmapDescriptor>();
	
	/**
	 * Get a bus icon (or cached one) for use in map
	 * @param bus Bus object
	 * @return BitmapDescriptor
	 */
	public BitmapDescriptor getBusIcon(Bus bus){
		BitmapDescriptor out = busIconCache.get(bus.lineid);
		if(out != null){
			return out;
		}
		out = getNewBusIcon(bus);
		busIconCache.put(bus.lineid, out);
		return out;
	}
	
	public BitmapDescriptor getNewBusIcon(Bus bus){
		switch(bus.lineid){
		case 1:
			return BitmapDescriptorFactory.fromResource(R.drawable.bus_red);
		case 2:
			return BitmapDescriptorFactory.fromResource(R.drawable.bus_blue);
		case 3:
			return BitmapDescriptorFactory.fromResource(R.drawable.bus_green);
		case 4:
			return BitmapDescriptorFactory.fromResource(R.drawable.bus_yellow);
		case 5:
			return BitmapDescriptorFactory.fromResource(R.drawable.bus_black);
		default:
			return BitmapDescriptorFactory.fromResource(R.drawable.bus_gray);
		}
	}
	
	private HashMap<String, BitmapDescriptor> stopIconCache = new HashMap<String, BitmapDescriptor>();
	
	/**
	 * Get a stop icon (or cached one) for use in map
	 * @param lineId Line ID
	 * @return BitmapDescriptor
	 */
	public BitmapDescriptor getStopIcon(String lineId){
		BitmapDescriptor out = stopIconCache.get(lineId);
		if(out != null){
			return out;
		}
		out = getNewStopIcon(lineId);
		stopIconCache.put(lineId, out);
		return out;
	}
	
	private BitmapDescriptor getNewStopIcon(String lineId) {
		int id = Integer.valueOf(lineId);
		switch(id){
		case 1:
			return BitmapDescriptorFactory.fromResource(R.drawable.pin_red);
		case 2:
			return BitmapDescriptorFactory.fromResource(R.drawable.pin_blue);
		case 3:
			return BitmapDescriptorFactory.fromResource(R.drawable.pin_green2);
		case 4:
			return BitmapDescriptorFactory.fromResource(R.drawable.pin_yellow);
		case 5:
			return BitmapDescriptorFactory.fromResource(R.drawable.pin_black);
		default:
			return BitmapDescriptorFactory.fromResource(R.drawable.pin_gray);
		}
	}

	public boolean isAllowBusStopClick() {
		return allowBusStopClick;
	}

	/**
	 * Whether tapping on bus stop infobox will show that bus stop's page
	 * Default is true
	 * @param allowBusStopClick
	 */
	public void setAllowBusStopClick(boolean allowBusStopClick) {
		this.allowBusStopClick = allowBusStopClick;
	}

	/**
	 * Connect to the bus position service and show bus in map
	 * If you use this, make sure you use onPause/onResume method
	 * in your activity/fragment and unregisterListener in your onDestroy
	 */
	public int registerListener(){
		if(listenerId != -1){
			return listenerId;
		}
		BusPosition.wsConnect();
		listenerId = BusPosition.registerUpdateListener(new ListenerList.Listener(){

			@Override
			public void onFired() {
				update(BusPosition.gets());
			}
			
		}, true);
		return listenerId;
	}
	
	/**
	 * Disconnect from the bus position service
	 * Make sure you run this on destroy when using registerListener
	 */
	public void unregisterListener(){
		BusPosition.removeUpdateListener(listenerId);
		listenerId = -1;
		BusPosition.wsDisconnect();
	}
	
	/**
	 * Reconnect to busposition service
	 */
	public void onResume(){
		if(listenerId != -1){
			BusPosition.wsConnect();
		}
	}
	
	/**
	 * Disconnect from busposition service
	 */
	public void onPause(){
		BusPosition.wsDisconnect();
	}
	
	@Override
	public void onInfoWindowClick(Marker marker) {
		if(!allowBusStopClick){
			return;
		}
		for(StopMarker mark : stopMarker){
			if(mark.marker.equals(marker)){
				try {
					// try opening by fragment first
					if(context instanceof MainActivity){
						MainActivity activity = (MainActivity) context;
						Log.d("BusMapController", mark.stop.toString());
						activity.showBusStop(mark.stop);
					}else{
						Intent intent = new Intent(context, MainActivity.class);
						intent.putExtra(MainActivity.OPEN_STOP, mark.stop.getInt("ID"));
						context.startActivity(intent);
					}
				} catch (JSONException e) {
				}
				break;
			}
		}
	}

	@Override
	public boolean onMarkerClick(Marker marker) {
		if(polylineRequestedByUser == 1){
			return false;
		}
		for(int i=0; i<markers.size(); i++){
			if(markers.valueAt(i).equals(marker)){
				int busid = markers.keyAt(i);
				Bus bus = BusPosition.get(busid);
				polylineRequestedByUser = 2;
				_drawPolyline(String.valueOf(bus.lineid));
				break;
			}
		}
		// show info box
		return false;
	}

	@Override
	public void onMapClick(LatLng point) {
		if(polylineRequestedByUser == 2){
			clearPolyline();
		}
	}

	public static class Filter{
		/**
		 * BUS: Filter by bus ID (1 bus)
		 * LINE: Filter by line ID (all bus of that line)
		 */
		public static enum FilterType {
			BUS,
			LINE
		}
		public FilterType type;
		public Integer value;
		public Filter(FilterType type, Integer value) {
			this.type = type;
			this.value = value;
		}
	}
	
	private class StopMarker{
		public Marker marker;
		public JSONObject stop;
		public StopMarker(Marker marker, JSONObject stop) {
			this.marker = marker;
			this.stop = stop;
		}
	}
}
