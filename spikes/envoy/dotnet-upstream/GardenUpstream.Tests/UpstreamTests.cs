using System.Net;
using System.Text.Json;
using Microsoft.AspNetCore.Mvc.Testing;
using Xunit;

namespace GardenUpstream.Tests;

public class UpstreamTests(WebApplicationFactory<Program> factory) : IClassFixture<WebApplicationFactory<Program>>
{
    private HttpClient Client() => factory.CreateClient();

    [Fact]
    public async Task A_bed_is_a_hal_representation_with_an_etag_and_a_harvest_link()
    {
        var response = await Client().GetAsync("/beds/1");

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.Equal("application/hal+json", response.Content.Headers.ContentType?.MediaType);
        Assert.NotNull(response.Headers.ETag);
        using var doc = JsonDocument.Parse(await response.Content.ReadAsStringAsync());
        Assert.Equal("/beds/1/harvest", doc.RootElement.GetProperty("_links").GetProperty("harvest").GetProperty("href").GetString());
    }

    [Fact]
    public async Task A_read_with_the_current_etag_is_not_modified()
    {
        var client = Client();
        var etag = (await client.GetAsync("/beds/2")).Headers.ETag!;

        var request = new HttpRequestMessage(HttpMethod.Get, "/beds/2");
        request.Headers.IfNoneMatch.Add(etag);
        var response = await client.SendAsync(request);

        Assert.Equal(HttpStatusCode.NotModified, response.StatusCode);
    }

    [Fact]
    public async Task Harvesting_answers_with_the_bed_and_names_it_in_content_location()
    {
        var client = Client();
        var etag = (await client.GetAsync("/beds/3")).Headers.ETag!;

        var request = new HttpRequestMessage(HttpMethod.Post, "/beds/3/harvest");
        request.Headers.IfMatch.Add(etag);
        var response = await client.SendAsync(request);

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.Equal("/beds/3", response.Content.Headers.ContentLocation?.OriginalString);
        Assert.NotEqual(etag, response.Headers.ETag);
        using var doc = JsonDocument.Parse(await response.Content.ReadAsStringAsync());
        Assert.Equal(1, doc.RootElement.GetProperty("harvests").GetInt32());
    }

    [Fact]
    public async Task Harvesting_with_a_stale_etag_is_refused()
    {
        var client = Client();
        var stale = (await client.GetAsync("/beds/4")).Headers.ETag!;
        var first = new HttpRequestMessage(HttpMethod.Post, "/beds/4/harvest");
        first.Headers.IfMatch.Add(stale);
        await client.SendAsync(first);

        var again = new HttpRequestMessage(HttpMethod.Post, "/beds/4/harvest");
        again.Headers.IfMatch.Add(stale);
        var response = await client.SendAsync(again);

        Assert.Equal(HttpStatusCode.PreconditionFailed, response.StatusCode);
    }
}
