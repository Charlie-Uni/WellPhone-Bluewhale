package com.octopus.agent.model

import android.app.Application
import com.octopus.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.*
import java.net.URL
import java.security.cert.Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class ChatTransportTest {
    private class Connection:HttpsURLConnection(URL("https://model.example.test/v1/chat/completions")) {
        var code=200;var responseType="application/json";var reads=0;var writes=0
        val output=ByteArrayOutputStream();var input:InputStream=ByteArrayInputStream(byteArrayOf())
        @Volatile var disconnected=false
        var onDisconnect:()->Unit={}
        override fun connect()=Unit
        override fun disconnect() {disconnected=true;onDisconnect()}
        override fun usingProxy()=false
        override fun getCipherSuite()="TEST_ONLY"
        override fun getLocalCertificates():Array<Certificate>?=null
        override fun getServerCertificates():Array<Certificate> = emptyArray()
        override fun getOutputStream():OutputStream {writes++;return output}
        override fun getInputStream():InputStream {reads++;return input}
        override fun getResponseCode()=code
        override fun getHeaderField(name:String?)=if(name.equals("Content-Type",true))responseType else null
        fun reply(content:String,finish:String="stop",extra:JSONObject=JSONObject()):Connection {
            val message=extra.put("role","assistant").put("content",content)
            input=ByteArrayInputStream(JSONObject().put("choices",JSONArray().put(JSONObject()
                .put("finish_reason",finish).put("message",message))).toString().toByteArray())
            return this
        }
    }
    private fun transport(connection:Connection,key:String="SYNTHETIC_TEST_KEY",metrics:MutableList<ModelMetric> = mutableListOf())=
        ChatTransport("https://model.example.test/v1/chat/completions","test-model",{key},connect={connection},metric={metrics+=it})
    private suspend fun failure(block:suspend()->Unit):ModelFailure {
        try {block();fail("Expected redacted failure")} catch(error:ModelFailure) {return error}
        error("unreachable")
    }
    @Test fun unicodeRequestHeadersAndJsonContractUseProductionTransport()=runTest {
        val connection=Connection().reply("{\"summary\":\"测试\",\"steps\":[\"读取\",\"核实\"]}")
        val metrics=mutableListOf<ModelMetric>()
        val result=transport(connection,metrics=metrics).complete("规则","合成招聘任务")
        assertTrue(result.contains("测试"));assertEquals("POST",connection.requestMethod)
        assertEquals("Bearer SYNTHETIC_TEST_KEY",connection.getRequestProperty("Authorization"))
        assertFalse(connection.instanceFollowRedirects);assertTrue(connection.disconnected)
        val request=JSONObject(connection.output.toString("UTF-8"))
        assertEquals("json_object",request.getJSONObject("response_format").getString("type"))
        assertEquals("合成招聘任务",request.getJSONArray("messages").getJSONObject(1).getString("content"))
        assertEquals("SUCCEEDED",metrics.single().status)
    }
    @Test fun unsafeEndpointsAndBlankKeysAreRejectedBeforeSending()=runTest {
        for(endpoint in listOf("http://model.example.test","https://user:pass@model.example.test","https://model.example.test/?key=secret","https://model.example.test/#fragment")) {
            var opened=false
            failure {ChatTransport(endpoint,"model",{"test"},connect={opened=true;Connection()}).complete("rules","input")}
            assertFalse(opened)
        }
        val connection=Connection();assertEquals("MODEL_KEY",failure {transport(connection,key="").complete("rules","input")}.code)
        assertEquals(0,connection.writes);assertTrue(connection.disconnected)
    }
    @Test fun redirectsAuthRateAndServerFailuresNeverReadBodyOrRetry()=runTest {
        for(code in listOf(302,401,429,500)) {
            val connection=Connection().apply {this.code=code;input=ByteArrayInputStream("PRIVATE_PROVIDER_ERROR".toByteArray())}
            assertEquals("MODEL_HTTP_$code",failure {transport(connection).complete("rules","input")}.code)
            assertEquals(0,connection.reads);assertEquals(1,connection.writes);assertTrue(connection.disconnected)
        }
    }
    @Test fun boundedBodyAndContentTypeFailClosed()=runTest {
        val large=Connection().apply {input=ByteArrayInputStream(ByteArray(256_001){32})}
        assertEquals("MODEL_RESPONSE_LIMIT",failure {transport(large).complete("rules","input")}.code)
        val html=Connection().apply {responseType="text/html"}
        assertEquals("MODEL_CONTENT_TYPE",failure {transport(html).complete("rules","input")}.code);assertEquals(0,html.reads)
    }
    @Test fun incompleteRefusalAndToolsCannotBecomeModelActions()=runTest {
        for((connection,code) in listOf(
            Connection().reply("{}","length") to "MODEL_INCOMPLETE",
            Connection().reply("{}",extra=JSONObject().put("refusal","private refusal")) to "MODEL_REFUSAL",
            Connection().reply("{}",extra=JSONObject().put("tool_calls",JSONArray().put(JSONObject()))) to "MODEL_TOOLS")) {
            assertEquals(code,failure {transport(connection).complete("rules","input")}.code)
        }
        assertEquals("{}",transport(Connection().reply("{}",extra=JSONObject().put("refusal",JSONObject.NULL))).complete("rules","input"))
    }
    @Test fun typedPlanDecisionAndExtractionDecodeWithoutRealNetwork()=runTest {
        val plan=TaskPlan("合成计划",listOf("读取","核实"))
        assertEquals(plan,HttpWorkflowModel(transport(Connection().reply(Json.encodeToString(plan)))).plan("合成任务"))
        val decision=Decision(Action.Wait(500),"synthetic")
        assertEquals(decision,HttpDecisionModel(transport(Connection().reply(Json.encodeToString(decision)))).decide("goal",
            Observation(SessionToken("synthetic",0),"o",2,7,"mail",0,emptyList())))
        val mail=MailDocument(SourceEmail("me@example.test","hr@example.test","招聘","2026-10-24T01:00:00Z","hash"),"synthetic")
        assertEquals(Extraction(emptyList()),HttpWorkflowModel(transport(Connection().reply("{\"items\":[]}")))
            .extract(WorkflowConfig("goal","me@example.test","Recruitment","me@example.test","Events"),mail,"synthetic"))
    }
    @Test fun invalidModelJsonAndOuterResponseDoNotExposePrivateText()=runTest {
        val private="DO_NOT_ECHO_THIS_RESPONSE"
        assertEquals("MODEL_JSON",failure {HttpWorkflowModel(transport(Connection().reply(private))).plan("synthetic")}.code)
        val connection=Connection().apply {input=ByteArrayInputStream(private.toByteArray())}
        val error=failure {transport(connection).complete("rules","input")}
        assertFalse(error.message!!.contains(private));assertNull(error.cause)
    }
    @Test fun cancellationDisconnectsBlockedReadAndWaitsForWorkerCleanup()=runTest {
        val connection=Connection();val entered=CompletableDeferred<Unit>();val release=CountDownLatch(1)
        connection.onDisconnect={release.countDown()}
        connection.input=object:InputStream() {
            override fun read():Int {entered.complete(Unit);check(release.await(5,TimeUnit.SECONDS));throw IOException("PRIVATE_NETWORK_ERROR")}
        }
        val pending=launch {transport(connection).complete("rules","input")}
        entered.await();pending.cancelAndJoin();assertTrue(connection.disconnected);assertEquals(0L,release.count)
    }
}
