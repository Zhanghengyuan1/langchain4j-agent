package com.example.agent.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** 阅读 4：一个 Tool 可调用两个 API：城市名 → 经纬度 → 当前天气。 */
@Component
public class OpenMeteoWeatherClient {
    private final ApiHttpClient http;

    public OpenMeteoWeatherClient(ApiHttpClient http) { this.http = http; }

    public WeatherData currentWeather(String city) {
        // 把用户输入作为 URI 变量严格编码，避免空格、中文、&、+ 改变查询参数结构。
        URI geocodingUri = UriComponentsBuilder.fromHttpUrl("https://geocoding-api.open-meteo.com/v1/search")
                .queryParam("name", "{city}").queryParam("count", 5)
                .queryParam("language", "zh").queryParam("format", "json")
                .encode().buildAndExpand(city).toUri();
        GeocodingResponse places = http.get(geocodingUri, GeocodingResponse.class);
        // Open-Meteo 无匹配时可以省略 results，不应把它当作网络失败。
        if (places.results() == null || places.results().isEmpty()) {
            throw new ExternalApiException("CITY_NOT_FOUND", "未找到城市，请换用英文名或补充省份、国家。");
        }
        if (places.results().stream().anyMatch(Objects::isNull)) throw ExternalApiException.invalidResponse();
        if (places.results().size() > 1) {
            String candidates = places.results().stream().limit(5).map(Place::label).collect(Collectors.joining("；"));
            throw new ExternalApiException("AMBIGUOUS_CITY", "匹配到多个地点，请用户补充城市、省份或国家：" + candidates);
        }
        Place place = places.results().get(0);
        if (place.name() == null || !coordinate(place.latitude(), 90) || !coordinate(place.longitude(), 180)) {
            throw ExternalApiException.invalidResponse();
        }

        URI weatherUri = UriComponentsBuilder.fromHttpUrl("https://api.open-meteo.com/v1/forecast")
                .queryParam("latitude", place.latitude()).queryParam("longitude", place.longitude())
                .queryParam("current", "temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m")
                .queryParam("temperature_unit", "celsius").queryParam("wind_speed_unit", "kmh")
                .queryParam("timezone", "auto").build().encode().toUri();
        WeatherResponse response = http.get(weatherUri, WeatherResponse.class);
        Current current = response.current();
        if (current == null || current.time() == null || response.timezone() == null
                || current.temperature() == null || !Double.isFinite(current.temperature())
                || current.humidity() == null || current.humidity() < 0 || current.humidity() > 100
                || current.windSpeed() == null || !Double.isFinite(current.windSpeed()) || current.windSpeed() < 0
                || current.weatherCode() == null) {
            // 包装类型 Double/Integer 用于识别缺失值，避免把缺失温度误报为 0°C。
            throw ExternalApiException.invalidResponse();
        }
        return new WeatherData("Open-Meteo (https://open-meteo.com/); location: GeoNames",
                "当前天气模型数据，不代表现场气象站实测", place.label(), place.latitude(), place.longitude(),
                current.time(), response.timezone(), current.temperature(), current.humidity(),
                current.windSpeed(), current.weatherCode(), describe(current.weatherCode()));
    }

    private static boolean coordinate(Double value, int bound) {
        return value != null && Double.isFinite(value) && Math.abs(value) <= bound;
    }

    private static String describe(int code) {
        return switch (code) {
            case 0 -> "晴";
            case 1, 2, 3 -> "晴间多云或阴";
            case 45, 48 -> "雾";
            case 51, 53, 55 -> "毛毛雨";
            case 56, 57, 66, 67 -> "冻雨或冻毛毛雨";
            case 61, 63, 65 -> "雨";
            case 71, 73, 75, 77 -> "雪";
            case 80, 81, 82 -> "阵雨";
            case 85, 86 -> "阵雪";
            case 95, 96, 99 -> "雷暴，可能伴有冰雹";
            default -> "未知天气代码：" + code;
        };
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GeocodingResponse(List<Place> results) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Place(String name, String admin1, String country, Double latitude, Double longitude) {
        String label() {
            return Stream.of(name, admin1, country).filter(Objects::nonNull)
                    .map(s -> s.substring(0, Math.min(s.length(), 100)))
                    .filter(s -> !s.isBlank()).distinct().collect(Collectors.joining(", "));
        }
    }
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WeatherResponse(String timezone, Current current) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Current(String time,
                          @JsonProperty("temperature_2m") Double temperature,
                          @JsonProperty("relative_humidity_2m") Integer humidity,
                          @JsonProperty("wind_speed_10m") Double windSpeed,
                          @JsonProperty("weather_code") Integer weatherCode) {}
    // 字段名直接带单位，模型不会把 km/h 当成 m/s；localTime 必须和 timezone 一起解释。
    public record WeatherData(String source, String dataKind, String matchedLocation, double latitude,
                              double longitude, String localTime, String timezone, double temperatureCelsius,
                              int relativeHumidityPercent, double windSpeedKmh, int weatherCode, String condition) {}
}
