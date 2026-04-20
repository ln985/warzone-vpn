package com.warzone.vpn;

import android.app.Activity;
import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int VPN_REQUEST_CODE = 1001;

    private Spinner spinnerProvince;
    private Spinner spinnerCity;
    private Spinner spinnerDistrict;
    private Button btnStartVpn;
    private TextView tvStatus;

    private JSONArray provinceData;

    private List<JSONObject> provinceList = new ArrayList<>();
    private List<JSONObject> cityList = new ArrayList<>();
    private List<JSONObject> districtList = new ArrayList<>();

    private String selectedProvince = "";
    private String selectedCity = "";
    private String selectedDistrict = "";
    private String selectedAdcode = "";

    private boolean isConnected = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        spinnerProvince = findViewById(R.id.spinner_province);
        spinnerCity = findViewById(R.id.spinner_city);
        spinnerDistrict = findViewById(R.id.spinner_district);
        btnStartVpn = findViewById(R.id.btn_start_vpn);
        tvStatus = findViewById(R.id.tv_status);

        try {
            provinceData = loadJsonFromAssets("warzone.json");
            initProvinceSpinner();
        } catch (Exception e) {
            Toast.makeText(this, "加载数据失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }

        btnStartVpn.setOnClickListener(v -> {
            if (isConnected) {
                disconnectVpn();
            } else {
                prepareAndConnect();
            }
        });
    }

    private JSONArray loadJsonFromAssets(String filename) throws Exception {
        InputStream is = getAssets().open(filename);
        byte[] buffer = new byte[is.available()];
        is.read(buffer);
        is.close();
        return new JSONArray(new String(buffer, StandardCharsets.UTF_8));
    }

    private void initProvinceSpinner() throws Exception {
        provinceList.clear();
        List<String> names = new ArrayList<>();
        names.add("-- 请选择省份 --");

        for (int i = 0; i < provinceData.length(); i++) {
            JSONObject obj = provinceData.getJSONObject(i);
            provinceList.add(obj);
            names.add(obj.getString("fullName"));
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerProvince.setAdapter(adapter);

        spinnerProvince.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position == 0) {
                    clearCityAndDistrict();
                    return;
                }
                try {
                    JSONObject province = provinceList.get(position - 1);
                    selectedProvince = province.getString("fullName");
                    selectedAdcode = province.getString("adcode");
                    initCitySpinner(province);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void initCitySpinner(JSONObject province) throws Exception {
        cityList.clear();
        districtList.clear();
        List<String> names = new ArrayList<>();
        names.add("-- 请选择城市 --");

        JSONArray cities = province.optJSONArray("list");
        if (cities == null || cities.length() == 0) {
            // 省直辖，无城市列表，直接跳过
            spinnerCity.setEnabled(false);
            spinnerDistrict.setEnabled(false);
            return;
        }

        spinnerCity.setEnabled(true);
        for (int i = 0; i < cities.length(); i++) {
            JSONObject obj = cities.getJSONObject(i);
            cityList.add(obj);
            names.add(obj.getString("fullName"));
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerCity.setAdapter(adapter);

        // 重置区县
        List<String> emptyDistrict = new ArrayList<>();
        emptyDistrict.add("-- 请选择区县 --");
        ArrayAdapter<String> districtAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, emptyDistrict);
        districtAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerDistrict.setAdapter(districtAdapter);
        spinnerDistrict.setEnabled(false);

        spinnerCity.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position == 0) {
                    selectedCity = "";
                    selectedDistrict = "";
                    return;
                }
                try {
                    JSONObject city = cityList.get(position - 1);
                    selectedCity = city.getString("fullName");
                    selectedAdcode = city.getString("adcode");
                    initDistrictSpinner(city);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void initDistrictSpinner(JSONObject city) throws Exception {
        districtList.clear();
        List<String> names = new ArrayList<>();
        names.add("-- 请选择区县 --");

        JSONArray districts = city.optJSONArray("list");
        if (districts == null || districts.length() == 0) {
            // 无区县，自动跳过区县选择
            spinnerDistrict.setEnabled(false);
            selectedDistrict = "";
            return;
        }

        spinnerDistrict.setEnabled(true);
        for (int i = 0; i < districts.length(); i++) {
            JSONObject obj = districts.getJSONObject(i);
            districtList.add(obj);
            names.add(obj.getString("fullName"));
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerDistrict.setAdapter(adapter);

        spinnerDistrict.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position == 0) {
                    selectedDistrict = "";
                    return;
                }
                try {
                    JSONObject district = districtList.get(position - 1);
                    selectedDistrict = district.getString("fullName");
                    selectedAdcode = district.getString("adcode");
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void clearCityAndDistrict() {
        selectedProvince = "";
        selectedCity = "";
        selectedDistrict = "";
        selectedAdcode = "";

        List<String> empty = new ArrayList<>();
        empty.add("-- 请选择城市 --");
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, empty);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerCity.setAdapter(adapter);
        spinnerCity.setEnabled(false);

        List<String> empty2 = new ArrayList<>();
        empty2.add("-- 请选择区县 --");
        ArrayAdapter<String> adapter2 = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, empty2);
        adapter2.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerDistrict.setAdapter(adapter2);
        spinnerDistrict.setEnabled(false);
    }

    private void prepareAndConnect() {
        if (selectedProvince.isEmpty()) {
            Toast.makeText(this, "请先选择地区", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent intent = VpnService.prepare(this);
        if (intent != null) {
            startActivityForResult(intent, VPN_REQUEST_CODE);
        } else {
            onActivityResult(VPN_REQUEST_CODE, Activity.RESULT_OK, null);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            connectVpn();
        } else if (requestCode == VPN_REQUEST_CODE) {
            Toast.makeText(this, "VPN 权限被拒绝", Toast.LENGTH_SHORT).show();
        }
    }

    private void connectVpn() {
        Intent intent = new Intent(this, WarzoneVpnService.class);
        intent.putExtra("province", selectedProvince);
        intent.putExtra("city", selectedCity);
        intent.putExtra("district", selectedDistrict);
        intent.putExtra("adcode", selectedAdcode);
        startService(intent);

        isConnected = true;
        btnStartVpn.setText("断开连接");
        btnStartVpn.setBackgroundColor(getResources().getColor(android.R.color.holo_red_dark));

        String region = selectedProvince;
        if (!selectedCity.isEmpty()) region += " > " + selectedCity;
        if (!selectedDistrict.isEmpty()) region += " > " + selectedDistrict;
        tvStatus.setText("已连接: " + region + "\n区划代码: " + selectedAdcode);
    }

    private void disconnectVpn() {
        Intent intent = new Intent(this, WarzoneVpnService.class);
        intent.putExtra("action", "disconnect");
        startService(intent);

        isConnected = false;
        btnStartVpn.setText("开始修改");
        btnStartVpn.setBackgroundColor(getResources().getColor(android.R.color.holo_green_dark));
        tvStatus.setText("未连接");
    }
}
