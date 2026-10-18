import Foundation
import AppKit
import Combine
import CloudMusicCore

@main struct ModelChecks {
    @MainActor static func main() async {
        var results:[String]=[]
        func check(_ condition:Bool,_ label:String) throws {guard condition else{throw MusicError(label)};results.append(label);print("PASS "+label)}
        do {
            let args=CommandLine.arguments
            guard let i=args.firstIndex(of:"--library-directory"),args.indices.contains(i+1),let j=args.firstIndex(of:"--local-fixture"),args.indices.contains(j+1) else{throw MusicError("Pass isolated --library-directory and --local-fixture")}
            let root=URL(fileURLWithPath:args[i+1],isDirectory:true),local=URL(fileURLWithPath:args[j+1],isDirectory:true),downloads=root.appendingPathComponent("ManualDownloads",isDirectory:true)
            try FileManager.default.createDirectory(at:downloads,withIntermediateDirectories:true)
            let defaults=UserDefaults(suiteName:"app.cloudmusic.macos."+stableID(root.path).prefix(12))!
            guard let bookmark=FileAccess.bookmark(downloads) else{throw MusicError("Test folder bookmark failed")}
            defaults.set(bookmark,forKey:"downloadDirectory")
            guard let folder=ProcessInfo.processInfo.environment["CLOUD_MUSIC_TEST_FOLDER"],!folder.isEmpty else{throw MusicError("Set CLOUD_MUSIC_TEST_FOLDER explicitly for live cloud checks")}
            let model=LibraryModel();model.player.volume=0
            try await wait(10){model.isReady}
            try check(model.library.sources.isEmpty && model.tracks.isEmpty,"Fresh library starts empty without a cloud link")
            model.connect(folder)
            try await wait(70){!model.busy}
            try check(model.alert==nil && model.library.tracks.count>=256,"Connect actual Yandex folder through app model")
            guard let track=model.tracks.first(where:{$0.title=="Dancing in My Room"}) else{throw MusicError("Expected live test track")}
            model.query="dancing IN my room"
            try check(model.visibleTracks.map(\.id)==[track.id],"Case-insensitive app search")
            model.play(track)
            try await wait(50){model.player.isPlaying && model.player.position>0.4}
            try check(model.player.queue.count==1,"Search selection keeps its queue context")
            model.player.seek(30);try await wait(12){model.player.position>=30}
            try check(model.player.isPlaying,"App player seek and stream")
            var playerUpdates=0
            let subscription=model.player.objectWillChange.sink{playerUpdates+=1}
            try await Task.sleep(nanoseconds:1_200_000_000)
            subscription.cancel()
            try check(playerUpdates<=1 && model.player.isPlaying,"Playback clock does not invalidate library-wide player observers")
            model.toggleFavorite(track);try check(model.library.favorites.contains(track.id),"Favorite state")
            model.navigate("favorites")
            try check(model.visibleTracks.map(\.id)==[track.id],"Cached listing updates after favorite navigation")
            model.query="no-such-track-for-cache-check"
            try check(model.visibleTracks.isEmpty,"Cached listing updates after search changes")
            model.query=""
            model.download(track);model.cancelDownload(track)
            try check(model.downloadStates[track.id]?.label=="Отменено" && model.player.isPlaying,"Cancelling a queued download preserves playback")
            model.download(track)
            try await wait(100){model.downloaded(track) || model.downloadStates[track.id]?.active==false}
            try check(model.downloaded(track),"Explicit cloud download and indexing")
            guard let saved=model.localURL(track) else{throw MusicError("Saved copy is missing")}
            try check(saved.isFileURL && (try Data(contentsOf:saved).count)>1000,"Cloud track resolves to saved file")
            model.player.pause()
            if let cover=model.tracks.first(where:{$0.title=="#20"}) {
                model.artwork.clear();model.artwork.request(cover,source:model.source(cover))
                try await wait(35){if model.artwork.image(cover)==nil{model.artwork.request(cover,source:model.source(cover))};return model.artwork.image(cover) != nil}
                try check(model.artwork.image(cover) != nil,"Decode actual embedded cloud artwork: \(model.artwork.failureReason[cover.id] ?? "no error")")
            }
            guard let source=model.source(track) else{throw MusicError("Source missing")}
            model.remove(source)
            try check(model.library.sources.allSatisfy{$0.id != source.id} && model.library.tracks.contains(where:{$0.id==track.id}),"Disconnecting source retains downloaded track")
            model.navigate("downloads");model.play(track)
            try await wait(15){model.player.isPlaying && model.player.position>0.4}
            try check(model.source(track)==nil,"Saved audio plays with cloud source absent")
            model.player.stop();await model.flush()
            let restored=LibraryModel();restored.player.volume=0
            try await wait(10){restored.library.downloads[track.id] != nil}
            try check(restored.library.favorites.contains(track.id) && restored.downloaded(track),"Library, favorites and download survive reload")
            restored.navigate("downloads");restored.play(track)
            try await wait(15){restored.player.isPlaying && restored.player.position>0.4};restored.player.stop()
            try check(true,"Offline copy reopens after model restart")
            restored.importURLs([local]);try await wait(30){!restored.busy}
            restored.openArtist("Cloud Music")
            try check(restored.visibleTracks.count==3 && restored.visibleTracks.allSatisfy{$0.provider == .local},"Local folder and artist-only filtering")
            guard let first=restored.visibleTracks.first else{throw MusicError("Local fixtures missing")}
            restored.play(first);try await wait(15){restored.player.isPlaying && restored.player.position>0.4}
            try check(restored.player.queue.count==3,"Artist-only local playback queue")
            restored.player.next();try await wait(15){restored.player.isPlaying && restored.player.index==1 && restored.player.position>0.4};restored.player.pause()
            try check(true,"Native next-track transition")
            restored.removeDownload(track)
            try check(!FileManager.default.fileExists(atPath:saved.path) && restored.library.downloads[track.id]==nil,"Remove only downloaded copy")
            try check(FileManager.default.fileExists(atPath:local.appendingPathComponent(first.filename).path),"Original local audio remains intact")
            restored.player.stop();await restored.flush()
            let report:[String:Any]=["passed":results.count,"checks":results,"device":"arm64 macOS","downloadsRemoved":true,"globalNetworkSettingsChanged":false]
            try JSONSerialization.data(withJSONObject:report,options:[.prettyPrinted,.sortedKeys]).write(to:root.appendingPathComponent("model-checks.json"),options:.atomic)
            print("\(results.count) model checks passed")
        }catch{fputs("MODEL CHECK FAILED: \(error.localizedDescription)\n",stderr);exit(1)}
    }
    @MainActor static func wait(_ timeout:Double,until condition:()->Bool) async throws {
        let deadline=Date().addingTimeInterval(timeout)
        while Date()<deadline{if condition(){return};try await Task.sleep(nanoseconds:100_000_000)}
        throw MusicError("Timed out after \(timeout)s")
    }
}
