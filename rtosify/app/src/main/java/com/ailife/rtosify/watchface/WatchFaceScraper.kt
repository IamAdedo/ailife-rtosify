package com.ailife.rtosify.watchface

import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

import android.util.Log

data class WatchFace(
    val title: String,
    val downloadUrl: String,
    val previewUrl: String? = null,
    val category: String = "ClockSkin"
)

data class ScrapeResult(
    val faces: List<WatchFace>,
    val threadId: Int
)

object WatchFaceScraper {
    private const val BASE_URL = "https://chujalt.com/"
    private const val USER_AGENT = "RTOSify/Android"

    /**
     * Scrapes watch faces from a specific thread or jb.html.
     * threadId == -1 -> jb.html
     * threadId > 0 -> jb/thread-threadId.html
     */
    fun scrapeWatchFaces(id: Int): ScrapeResult {
        Log.d("WatchFaceScraper", "Scraping threadId $id")
        val faces = mutableListOf<WatchFace>()
        var detectedId = id

        try {
            var url = if (id == -1) {
                "${BASE_URL}jb.html"
            } else {
                "${BASE_URL}jb/thread-$id.html"
            }
            Log.d("WatchFaceScraper", "Fetching URL: $url")

            var doc = Jsoup.connect(url)
                .timeout(10000)
                .userAgent(USER_AGENT)
                .followRedirects(true)
                .get()
            
            Log.d("WatchFaceScraper", "Fetched doc size: ${doc.html().length}")

            // Handle META refresh redirect if present
            val metaRefresh = doc.select("meta[http-equiv=refresh]").firstOrNull()
            val refreshContent = metaRefresh?.attr("content")
            if (refreshContent != null) {
                val urlIndex = refreshContent.indexOf("url=", ignoreCase = true)
                if (urlIndex != -1) {
                    val redirectUrl = refreshContent.substring(urlIndex + 4).trim()
                    if (redirectUrl.isNotEmpty()) {
                        val finalUrl = if (redirectUrl.startsWith("http")) redirectUrl else {
                            if (redirectUrl.startsWith("/")) BASE_URL + redirectUrl.removePrefix("/")
                            else url.substringBeforeLast("/") + "/" + redirectUrl
                        }
                        Log.d("WatchFaceScraper", "Following META refresh to: $finalUrl")
                        
                        // Detect thread ID from redirect URL
                        val threadMatch = Regex("thread-(\\d+)").find(finalUrl)
                        threadMatch?.groupValues?.get(1)?.toIntOrNull()?.let { 
                            detectedId = it 
                            Log.d("WatchFaceScraper", "Detected Thread ID from redirect: $detectedId")
                        }

                        doc = Jsoup.connect(finalUrl)
                            .timeout(10000)
                            .userAgent(USER_AGENT)
                            .followRedirects(true)
                            .get()
                        Log.d("WatchFaceScraper", "Fetched redirect doc size: ${doc.html().length}")
                    }
                }
            } else {
                // Try to detect ID from current URL if no redirect
                val threadMatch = Regex("thread-(\\d+)").find(doc.baseUri())
                threadMatch?.groupValues?.get(1)?.toIntOrNull()?.let { detectedId = it }
            }

            // Improved parsing logic based on parse_watchfaces_improved.py
            val images = doc.select("img")
            Log.d("WatchFaceScraper", "Found ${images.size} total images")
            for (img in images) {
                val src = img.attr("data-src").ifEmpty { img.attr("src") }
                if (src.isBlank()) continue
                
                if (!src.contains("/watchfaces/")) continue

                // Use absolute URL from Jsoup if possible, or build manually
                val absoluteImgUrl = img.absUrl("src").ifEmpty {
                    img.absUrl("data-src").ifEmpty {
                        if (src.startsWith("http")) src else {
                            if (src.startsWith("/")) BASE_URL + src.removePrefix("/")
                            else doc.baseUri().substringBeforeLast("/") + "/" + src
                        }
                    }
                }
                Log.d("WatchFaceScraper", "Found watchface image: $absoluteImgUrl")
                
                // Title cleanup
                var title = img.attr("alt")
                    .replace("[Imagen: ", "")
                    .replace("]", "")
                    .trim()
                
                val filename = absoluteImgUrl.substringAfterLast("/")
                val nameWithoutExt = filename.substringBeforeLast(".")
                
                if (title.isBlank() || title == filename) {
                    title = nameWithoutExt
                }

                // Download URL is same as image URL but with .watch extension
                val downloadUrl = absoluteImgUrl.substringBeforeLast(".") + ".watch"

                faces.add(WatchFace(
                    title = title,
                    downloadUrl = downloadUrl,
                    previewUrl = absoluteImgUrl
                ))
            }

            // Also look for explicit .watch links as a backup
            val watchLinks = doc.select("a[href$=.watch]")
            for (link in watchLinks) {
                val downloadUrl = link.absUrl("href")
                val title = link.text().ifEmpty { downloadUrl.substringAfterLast("/").substringBeforeLast(".") }
                
                // Avoid duplicates
                if (faces.none { it.downloadUrl == downloadUrl }) {
                    faces.add(WatchFace(
                        title = title,
                        downloadUrl = downloadUrl,
                        previewUrl = null
                    ))
                }
            }

        } catch (e: Exception) {
            Log.e("WatchFaceScraper", "Error scraping ID $id", e)
        }
        
        val finalFaces = faces.distinctBy { it.downloadUrl }
        Log.d("WatchFaceScraper", "Scraped ${finalFaces.size} faces from ID $id, detected ID: $detectedId")
        return ScrapeResult(finalFaces, detectedId)
    }
}
