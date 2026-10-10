package com.artie.buster.bdd

import com.artie.buster.core.BusterResponse
import com.artie.buster.core.Classification
import com.artie.buster.core.Classifier
import com.artie.buster.core.Dialect
import com.artie.buster.core.Headers
import com.artie.buster.core.Layer
import com.artie.buster.core.Rung
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertEquals

class ClassificationSteps {
    private var status = 0
    private val headers = Headers()
    private var body = ByteArray(0)
    private lateinit var result: Classification

    @Given("a response with status {int} and Content-Type {string}")
    fun aResponse(status: Int, contentType: String) {
        this.status = status
        headers["Content-Type"] = contentType
    }

    @Given("Cache-Control {string}, ETag {string} and Preference-Applied {string}")
    fun cachingHeaders(cacheControl: String, etag: String, applied: String) {
        headers["Cache-Control"] = cacheControl
        if (etag.isNotEmpty()) headers["ETag"] = "\"$etag\""
        if (applied.isNotEmpty()) headers["Preference-Applied"] = applied
    }

    @Given("the body is the {string} fixture")
    fun theBody(name: String) {
        body = checkNotNull(javaClass.getResourceAsStream("/fixtures/$name.json")) { "no fixture $name" }
            .use { it.readBytes() }
    }

    @When("Buster classifies it")
    fun classify() {
        result = Classifier.classify(BusterResponse(status, headers, body))
    }

    @Then("the layer is {word} and the rung is {word}")
    fun layerAndRung(layer: String, rung: String) {
        assertEquals(Layer.valueOf(layer) to Rung.valueOf(rung), result.layer to result.rung)
    }

    @Then("the dialect is {word}")
    fun dialect(dialect: String) {
        assertEquals(Dialect.valueOf(dialect), result.dialect)
    }
}
