package de.bsommerfeld.wsbg.terminal.briefing;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the live-probed shapes (2026-07-14): Open-Meteo's multi-coordinate
 * ARRAY reply with the daily block (index 1 = tomorrow), NHC's activeStorms,
 * USGS's GeoJSON with PAGER/tsunami flags, and the FAA airspace XML whose
 * closure entries are GA NOTAM noise and must stay out.
 */
class WorldDataClientsTest {

    // ---- Open-Meteo --------------------------------------------------------

    private static final String METEO_ARRAY = """
            [
              {"current":{"time":"2026-07-14T13:30","temperature_2m":24.8,"weather_code":82,"wind_speed_10m":5.5},
               "daily":{"time":["2026-07-14","2026-07-15"],"temperature_2m_max":[27.1,26.5],
                        "temperature_2m_min":[23.1,23.1],"weather_code":[82,80]}},
              {"current":{"time":"2026-07-14T20:30","temperature_2m":31.2,"weather_code":0,"wind_speed_10m":48.0},
               "daily":{"time":["2026-07-14","2026-07-15"],"temperature_2m_max":[33.0,35.5],
                        "temperature_2m_min":[24.0,25.0],"weather_code":[0,1]}}
            ]
            """;

    @Test
    void meteoArrayMapsToPlacesInOrderWithTomorrow() {
        List<WorldWeatherClient.PlaceWeather> out = WorldWeatherClient.parse(METEO_ARRAY);
        assertEquals(2, out.size());
        WorldWeatherClient.PlaceWeather first = out.get(0);
        assertEquals(WorldWeatherClient.PLACES.get(0).name(), first.place());
        assertEquals(24.8, first.tempC());
        assertEquals("Schauer", first.word());
        assertEquals(26.5, first.tomorrowMaxC());
        assertEquals("Schauer", first.tomorrowWord());
        assertEquals("klar", out.get(1).word());
    }

    @Test
    void meteoCodeWordsBucketTheWmoTable() {
        assertEquals("klar", WorldWeatherClient.codeWord(0));
        assertEquals("heiter", WorldWeatherClient.codeWord(2));
        assertEquals("bedeckt", WorldWeatherClient.codeWord(3));
        assertEquals("Nebel", WorldWeatherClient.codeWord(45));
        assertEquals("Regen", WorldWeatherClient.codeWord(63));
        assertEquals("Gewitter", WorldWeatherClient.codeWord(95));
    }

    @Test
    void meteoGarbageYieldsEmpty() {
        assertTrue(WorldWeatherClient.parse(null).isEmpty());
        assertTrue(WorldWeatherClient.parse("<html>wall</html>").isEmpty());
    }

    // ---- NHC ---------------------------------------------------------------

    @Test
    void nhcEmptySeasonIsEmptyNotError() {
        assertTrue(GlobalHazardsClient.parseStorms("{\"activeStorms\": []}").isEmpty());
    }

    @Test
    void nhcActiveStormRendersClassificationAndBasin() {
        String body = """
                {"activeStorms":[{"id":"al062026","binNumber":"AT1","name":"Erin",
                  "classification":"HU","intensity":"90","pressure":"955"}]}
                """;
        List<GlobalHazardsClient.Hazard> out = GlobalHazardsClient.parseStorms(body);
        assertEquals(1, out.size());
        assertTrue(out.get(0).text().contains("Hurrikan Erin"));
        assertTrue(out.get(0).text().contains("90 kt"));
        assertEquals("HIGH", out.get(0).severity());
        assertEquals("STORM", out.get(0).kind());
    }

    // ---- USGS --------------------------------------------------------------

    @Test
    void usgsFiltersSmallQuakesAndKeepsFlaggedOnes() {
        String body = """
                {"features":[
                  {"properties":{"mag":6.2,"place":"34 km WSW of Sarangani, Philippines",
                    "alert":"green","tsunami":0}},
                  {"properties":{"mag":4.6,"place":"somewhere quiet","alert":"green","tsunami":0}},
                  {"properties":{"mag":5.0,"place":"near the coast","alert":"orange","tsunami":1}}
                ]}
                """;
        List<GlobalHazardsClient.Hazard> out = GlobalHazardsClient.parseQuakes(body);
        assertEquals(2, out.size());
        assertTrue(out.get(0).text().startsWith("M6.2"));
        assertTrue(out.get(1).text().contains("Tsunami-Warnung"));
        assertEquals("HIGH", out.get(1).severity());
    }

    // ---- FAA ---------------------------------------------------------------

    private static final String FAA_XML = """
            <AIRPORT_STATUS_INFORMATION><Update_Time>Tue Jul 14 18:37:26 2026 GMT</Update_Time>
            <Delay_type><Name>Ground Stop Programs</Name><Ground_Stop_List>
              <Program><ARPT>DCA</ARPT><Reason>VIP</Reason><End_Time>3:00 pm EDT</End_Time></Program>
            </Ground_Stop_List></Delay_type>
            <Delay_type><Name>Ground Delay Programs</Name><Ground_Delay_List>
              <Ground_Delay><ARPT>SFO</ARPT><Reason>other</Reason><Avg>45 minutes</Avg><Max>1 hour and 40 minutes</Max></Ground_Delay>
            </Ground_Delay_List></Delay_type>
            <Delay_type><Name>General Arrival/Departure Delay Info</Name><Arrival_Departure_Delay_List>
              <Delay><ARPT>DFW</ARPT><Reason>WX:Thunderstorms</Reason>
                <Arrival_Departure Type="Departure"><Min>16 minutes</Min><Max>30 minutes</Max><Trend>Increasing</Trend></Arrival_Departure></Delay>
            </Arrival_Departure_Delay_List></Delay_type>
            <Delay_type><Name>Airport Closures</Name><Airport_Closure_List>
              <Airport><ARPT>EWR</ARPT><Reason>!EWR 06/034 EWR AD AP CLSD TO TRANSIENT GA ACFT</Reason>
                <Start>Jun 06 at 04:00 UTC.</Start><Reopen>Jul 20 at 03:59 UTC.</Reopen></Airport>
            </Airport_Closure_List></Delay_type>
            </AIRPORT_STATUS_INFORMATION>
            """;

    @Test
    void faaParsesStopsDelaysAndSkipsClosureNoise() {
        List<GlobalHazardsClient.Hazard> out = GlobalHazardsClient.parseFaa(FAA_XML);
        assertEquals(3, out.size());
        assertTrue(out.get(0).text().contains("DCA: Ground Stop bis 3:00 pm EDT"));
        assertEquals("HIGH", out.get(0).severity());
        assertTrue(out.get(1).text().contains("SFO: Ground Delay"));
        assertTrue(out.get(1).text().contains("Ø 45 minutes"));
        assertTrue(out.get(2).text().contains("DFW"));
        assertTrue(out.get(2).text().contains("Wetter: Thunderstorms"));
        assertTrue(out.stream().noneMatch(h -> h.text().contains("EWR")));
    }

    @Test
    void hazardGarbageYieldsEmpty() {
        assertTrue(GlobalHazardsClient.parseStorms("<html>").isEmpty());
        assertTrue(GlobalHazardsClient.parseQuakes(null).isEmpty());
        assertTrue(GlobalHazardsClient.parseFaa("{json}").isEmpty());
    }
}
