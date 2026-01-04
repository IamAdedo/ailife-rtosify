package com.ailife.rtosify.watchface

import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

data class WatchFace(
    val title: String,
    val downloadUrl: String,
    val previewUrl: String? = null,
    val category: String = "ClockSkin"
)

object WatchFaceScraper {
    private const val BASE_URL = "https://chujalt.com/"
    
    // Predefined forum IDs to scrape
    private val FORUMS = listOf(35, 36, 37, 38) // Cuadradas/Redondas for ClockSkin/Watch

    fun scrapeWatchFaces(page: Int = 1): List<WatchFace> {
        val result = mutableListOf<WatchFace>()
        
        for (fid in FORUMS) {
            try {
                // MyBB forum list URL: forum-X.html or forumdisplay.php?fid=X&page=Y
                val url = "${BASE_URL}forum-$fid.html?page=$page"
                val doc = Jsoup.connect(url)
                    .timeout(10000)
                    .userAgent("RTOSify/Android")
                    .get()
                
                // On MyBB, threads are usually in links with class "subject_new" or "subject_old"
                // Inside <td class="trow1" or trow2>
                val threads = doc.select("span.subject_new a, span.subject_old a")
                for (thread in threads) {
                    val threadUrl = thread.absUrl("href")
                    val threadTitle = thread.text()
                    
                    if (threadUrl.isEmpty()) continue
                    
                    // Now we need to visit the thread to get the zip files
                    // For performance, we might want to do this lazily, but let's do it now for simplicity
                    val faceDetails = scrapeThreadDetails(threadUrl)
                    result.addAll(faceDetails)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        return result
    }

    private fun scrapeThreadDetails(threadUrl: String): List<WatchFace> {
        val faces = mutableListOf<WatchFace>()
        try {
            val doc = Jsoup.connect(threadUrl)
                .timeout(5000)
                .userAgent("RTOSify/Android")
                .get()
            
            // Look for zip/watch links
            val links = doc.select("a[href$=.zip], a[href$=.watch]")
            
            // Look for any image in the post that might be a preview
            val previewImg = doc.select("div.post_body img").firstOrNull()?.absUrl("src")

            for (link in links) {
                val downloadUrl = link.absUrl("href")
                val name = link.text().ifEmpty { downloadUrl.substringAfterLast("/") }
                
                faces.add(WatchFace(
                    title = name,
                    downloadUrl = downloadUrl,
                    previewUrl = previewImg // Use the first found image as preview for all zips in this thread
                ))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return faces
    }
}
