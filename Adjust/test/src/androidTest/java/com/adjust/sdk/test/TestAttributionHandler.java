package com.adjust.sdk.test;

import android.content.Context;
import android.os.SystemClock;
import android.test.ActivityInstrumentationTestCase2;

import com.adjust.sdk.ActivityHandler;
import com.adjust.sdk.ActivityPackage;
import com.adjust.sdk.AdjustConfig;
import com.adjust.sdk.AdjustFactory;
import com.adjust.sdk.AttributionHandler;

import org.apache.http.client.methods.HttpUriRequest;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Created by pfms on 28/01/15.
 */
public class TestAttributionHandler extends ActivityInstrumentationTestCase2<UnitTestActivity> {
    private MockLogger mockLogger;
    private MockActivityHandler mockActivityHandler;
    private MockHttpClient mockHttpClient;
    private AssertUtil assertUtil;
    private UnitTestActivity activity;
    private Context context;
    private ActivityPackage attributionPackage;

    public TestAttributionHandler() {
        super(UnitTestActivity.class);
    }

    public TestAttributionHandler(Class<UnitTestActivity> activityClass) {
        super(activityClass);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mockLogger = new MockLogger();
        mockActivityHandler = new MockActivityHandler(mockLogger);
        mockHttpClient = new MockHttpClient(mockLogger);

        assertUtil = new AssertUtil(mockLogger);

        AdjustFactory.setLogger(mockLogger);
        AdjustFactory.setActivityHandler(mockActivityHandler);
        AdjustFactory.setHttpClient(mockHttpClient);

        activity = getActivity();
        context = activity.getApplicationContext();

        attributionPackage = getAttributionPackage();
    }

    private ActivityPackage getAttributionPackage() {
        MockAttributionHandler mockAttributionHandler = new MockAttributionHandler(mockLogger);
        MockPackageHandler mockPackageHandler = new MockPackageHandler(mockLogger);

        AdjustFactory.setAttributionHandler(mockAttributionHandler);
        AdjustFactory.setPackageHandler(mockPackageHandler);

        // create the config to start the session
        AdjustConfig config = new AdjustConfig(context, "123456789012", AdjustConfig.ENVIRONMENT_SANDBOX);

        // start activity handler with config
        ActivityHandler activityHandler = ActivityHandler.getInstance(config);
        SystemClock.sleep(3000);

        ActivityPackage attributionPackage = activityHandler.getAttributionPackage();

        TestActivityPackage attributionPackageTest = new TestActivityPackage(attributionPackage);

        attributionPackageTest.testAttributionPackage();

        mockLogger.reset();

        return attributionPackage;
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();

        AdjustFactory.setActivityHandler(null);
        AdjustFactory.setLogger(null);

        activity = null;
        context = null;
    }

    public void testGetAttribution() {
        // assert test name to read better in logcat
        mockLogger.Assert("TestAttributionHandler testGetAttribution");

        AttributionHandler attributionHandler = new AttributionHandler(mockActivityHandler,
                attributionPackage, false);

        // test null client
        nullClientTest(attributionHandler);

        // test client exception
        clientExceptionTest(attributionHandler);

        // test wrong json response
        wrongJsonTest(attributionHandler);

        // test empty response
        emptyJsonResponseTest(attributionHandler);

        // test server error
        serverErrorTest(attributionHandler);

        // test ok response with message
        okMessageTest(attributionHandler);
    }

    public void testCheckAttribution() {
        // assert test name to read better in logcat
        mockLogger.Assert("TestAttributionHandler testCheckAttribution");

        AttributionHandler attributionHandler = new AttributionHandler(mockActivityHandler,
                attributionPackage, false);

        // test attribution with update in activity handler
        attributionResponseTest(attributionHandler, true);

        // test attribution without update in activity handler
        attributionResponseTest(attributionHandler, false);
    }

    public void testAskIn() {
        // assert test name to read better in logcat
        mockLogger.Assert("TestAttributionHandler testAskIn");

        AttributionHandler attributionHandler = new AttributionHandler(mockActivityHandler,
                attributionPackage, false);

        String response = "Response: { \"ask_in\" : 4000 }";

        callCheckAttributionWithGet(attributionHandler, ResponseType.ASK_IN, response);

        // change the response to avoid a cycle;
        mockHttpClient.responseType = ResponseType.MESSAGE;

        // check attribution was called with ask_in
        assertUtil.notInTest("ActivityHandler tryUpdateAttribution");

        // it did update to true
        assertUtil.test("ActivityHandler setAskingAttribution, true");

        // and waited to for query
        assertUtil.debug("Waiting to query attribution in 4000 milliseconds");

        SystemClock.sleep(2000);

        JSONObject askInJsonResponse = null;
        try {
            askInJsonResponse = new JSONObject("{ \"ask_in\" : 5000 }");
        } catch (JSONException e) {
            fail(e.getMessage());
        }

        attributionHandler.checkAttribution(askInJsonResponse);

        SystemClock.sleep(3000);

        // it was been waiting for 1000 + 2000 + 3000 = 6 seconds
        // check that the mock http client was not called because the original clock was reseted
        assertUtil.notInTest("HttpClient execute");

        // check that it was finally called after 6 seconds after the second ask_in
        SystemClock.sleep(3000);

        // it did update to true
        assertUtil.test("ActivityHandler setAskingAttribution, true");

        // and waited to for query
        assertUtil.debug("Waiting to query attribution in 5000 milliseconds");

        okMessageTestLogs();

        requestTest(mockHttpClient.lastRequest);
    }

    private void nullClientTest(AttributionHandler attributionHandler) {
        startGetAttributionTest(attributionHandler, null);

        // check response was not logged
        assertUtil.notInVerbose("Response");
    }

    private void clientExceptionTest(AttributionHandler attributionHandler) {
        startGetAttributionTest(attributionHandler, ResponseType.CLIENT_PROTOCOL_EXCEPTION);

        // check the client error
        assertUtil.error("Failed to get attribution (testResponseError)");
    }

    private void wrongJsonTest(AttributionHandler attributionHandler) {
        startGetAttributionTest(attributionHandler, ResponseType.WRONG_JSON);

        // check that the mock http client was called
        assertUtil.test("HttpClient execute");

        assertUtil.verbose("Response: not a json response");

        assertUtil.error("Failed to parse json response: not a json response (Value not of type java.lang.String cannot be converted to JSONObject)");
    }

    private void emptyJsonResponseTest(AttributionHandler attributionHandler) {
        startGetAttributionTest(attributionHandler, ResponseType.EMPTY_JSON);

        // check that the mock http client was called
        assertUtil.test("HttpClient execute");

        assertUtil.verbose("Response: { }");

        assertUtil.info("No message found");

        // check attribution was called without ask_in
        assertUtil.test("ActivityHandler tryUpdateAttribution, null");
    }

    private void serverErrorTest(AttributionHandler attributionHandler) {
        startGetAttributionTest(attributionHandler, ResponseType.INTERNAL_SERVER_ERROR);

        // check that the mock http client was called
        assertUtil.test("HttpClient execute");

        // the response logged
        assertUtil.verbose("Response: { \"message\": \"testResponseError\"}");

        // the message in the response
        assertUtil.error("testResponseError");

        // check attribution was called without ask_in
        assertUtil.test("ActivityHandler tryUpdateAttribution, null");

        assertUtil.test("ActivityHandler setAskingAttribution, false");
    }

    private void okMessageTest(AttributionHandler attributionHandler) {
        startGetAttributionTest(attributionHandler, ResponseType.MESSAGE);

        okMessageTestLogs();
    }

    private void okMessageTestLogs() {
        // check that the mock http client was called
        assertUtil.test("HttpClient execute");

        // the response logged
        assertUtil.verbose("Response: { \"message\" : \"response OK\"}");

        // the message in the response
        assertUtil.info("response OK");

        // check attribution was called without ask_in
        assertUtil.test("ActivityHandler tryUpdateAttribution, null");
    }

    private void callCheckAttributionWithGet(AttributionHandler attributionHandler,
                                             ResponseType responseType,
                                             String response) {
        startGetAttributionTest(attributionHandler, responseType);

        // check that the mock http client was called
        assertUtil.test("HttpClient execute");

        // the response logged
        assertUtil.verbose(response);
    }

    private void attributionResponseTest(AttributionHandler attributionHandler, boolean updated) {
        String response = "Response: { \"attribution\" : " +
                "{\"tracker_token\" : \"ttValue\" , " +
                "\"tracker_name\"  : \"tnValue\" , " +
                "\"network\"       : \"nValue\" , " +
                "\"campaign\"      : \"cpValue\" , " +
                "\"adgroup\"       : \"aValue\" , " +
                "\"creative\"      : \"ctValue\" } }";

        mockActivityHandler.updated = updated;

        callCheckAttributionWithGet(attributionHandler, ResponseType.ATTRIBUTION, response);

        // check attribution was called without ask_in
        assertUtil.test("ActivityHandler tryUpdateAttribution, tt:ttValue tn:tnValue net:nValue cam:cpValue adg:aValue cre:ctValue");

        // updated set askingAttribution to false
        assertUtil.test("ActivityHandler setAskingAttribution, false");

        // it did not update to true
        assertUtil.notInTest("ActivityHandler setAskingAttribution, true");

        // and waiting for query
        assertUtil.notInDebug("Waiting to query attribution");
    }

    private void startGetAttributionTest(AttributionHandler attributionHandler, ResponseType responseType) {
        mockHttpClient.responseType = responseType;

        attributionHandler.getAttribution();

        SystemClock.sleep(1000);

        requestTest(mockHttpClient.lastRequest);
    }

    private void requestTest(HttpUriRequest request) {
        if (request == null) return;

        java.net.URI uri = request.getURI();

        assertUtil.isEqual("https", uri.getScheme());

        assertUtil.isEqual("app.adjust.com", uri.getAuthority());

        assertUtil.isEqual("GET", request.getMethod());
    }
}