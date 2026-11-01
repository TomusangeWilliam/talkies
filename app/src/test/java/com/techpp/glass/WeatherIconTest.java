package com.techpp.glass;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class WeatherIconTest {
    @Test
    public void mapsCommonConditionsToTheRightIcons() {
        assertEquals(R.drawable.ic_weather_cloudy, HomeActivity.weatherIconFor(null));
        assertEquals(R.drawable.ic_weather_sunny, HomeActivity.weatherIconFor("27° Sunny"));
        assertEquals(R.drawable.ic_weather_cloudy, HomeActivity.weatherIconFor("21° Overcast"));
        assertEquals(R.drawable.ic_weather_rain, HomeActivity.weatherIconFor("19° Heavy rain"));
        assertEquals(R.drawable.ic_weather_snow, HomeActivity.weatherIconFor("-2° Snow"));
        assertEquals(R.drawable.ic_weather_thunder, HomeActivity.weatherIconFor("31° Thunderstorm"));
        assertEquals(R.drawable.ic_weather_cloudy, HomeActivity.weatherIconFor("14° Fog"));
    }
}
