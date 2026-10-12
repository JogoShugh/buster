using System.Collections.Concurrent;
using System.Text.Json;
using System.Text.Json.Serialization;

// A small, honest HAL service: garden beds you can read and harvest.
// It is not flaky. Put Buster in front of it and Buster supplies the faults.

var builder = WebApplication.CreateBuilder(args);
var app = builder.Build();

// Harvest count per bed, in memory. The count is the whole state, and it makes the ETag.
var harvests = new ConcurrentDictionary<int, int>();

app.MapGet("/health", () => Results.Text("ok"));

app.MapGet("/beds/{id:int}", (int id, HttpContext http) =>
{
    var etag = ETagOf(id, harvests.GetValueOrDefault(id));
    if (http.Request.Headers.IfNoneMatch == etag)
    {
        http.Response.Headers.ETag = etag;
        return Results.StatusCode(StatusCodes.Status304NotModified);
    }
    return Representation(http, id, etag);
});

app.MapPost("/beds/{id:int}/harvest", (int id, HttpContext http) =>
{
    var current = ETagOf(id, harvests.GetValueOrDefault(id));
    // If-Match guards the act: the caller acts on the state it read, or learns it is stale.
    if (http.Request.Headers.IfMatch.Count > 0 && http.Request.Headers.IfMatch != current)
    {
        return Results.StatusCode(StatusCodes.Status412PreconditionFailed);
    }
    var count = harvests.AddOrUpdate(id, 1, (_, n) => n + 1);
    http.Response.Headers.ContentLocation = $"/beds/{id}";
    return Representation(http, id, ETagOf(id, count));
});

app.Run();

static string ETagOf(int id, int harvested) => $"\"bed-{id}-{harvested}\"";

IResult Representation(HttpContext http, int id, string etag)
{
    var bed = new Bed(id, $"Bed {id}", harvests.GetValueOrDefault(id), new BedLinks(
        new Link($"/beds/{id}"),
        new Link($"/beds/{id}/harvest")));
    http.Response.Headers.ETag = etag;
    http.Response.Headers.CacheControl = "private, no-cache";
    return Results.Text(JsonSerializer.Serialize(bed), "application/hal+json");
}

record Link([property: JsonPropertyName("href")] string Href);

record BedLinks(
    [property: JsonPropertyName("self")] Link Self,
    [property: JsonPropertyName("harvest")] Link Harvest);

record Bed(
    [property: JsonPropertyName("id")] int Id,
    [property: JsonPropertyName("name")] string Name,
    [property: JsonPropertyName("harvests")] int Harvests,
    [property: JsonPropertyName("_links")] BedLinks Links);

// Lets the test project start the app in memory.
public partial class Program;
