import Foundation
import AVFoundation
import CloudMusicCore

@main struct Check {
    @MainActor static func main() async {
        do {
            let args=CommandLine.arguments
            if let f=args.firstIndex(of:"--fixture"),args.indices.contains(f+1){try await fixture(args[f+1]);return}
            if args.contains("--self-test"){try await selfTest();return}
            guard let i=args.firstIndex(of:"--folder"),args.indices.contains(i+1) else{print("Usage: cloud-music-check --self-test | --folder URL [--play] [--report PATH]");return}
            let client=CloudClient(),folder=try FolderLink.parse(args[i+1]),start=Date(),contents=try await client.readFolder(folder)
            var report:[String:Any] = ["provider":folder.provider.rawValue,"tracks":contents.tracks.count,"folder":contents.source.name,"listingSeconds":Date().timeIntervalSince(start)]
            if args.contains("--play"),let track=contents.tracks.first(where:{$0.title=="Dancing in My Room"}) ?? contents.tracks.first {
                let began=Date(),url=try await client.resolve(track,source:contents.source),prefix=try await HTTP.read(url,limit:4096,range:4096)
                report["prefixBytes"]=prefix.data.count
                let player=AVPlayer(url:url);player.automaticallyWaitsToMinimizeStalling=false;player.currentItem?.preferredForwardBufferDuration=8;player.play()
                let end=Date().addingTimeInterval(45)
                while Date()<end && player.currentTime().seconds<0.7 && player.currentItem?.status != .failed {try await Task.sleep(nanoseconds:100_000_000)}
                guard player.currentItem?.status != .failed,player.currentTime().seconds>=0.7 else{throw MusicError("Native AVPlayer did not start")}
                report["playedTitle"]=track.title;report["startupSeconds"]=Date().timeIntervalSince(began);await player.seek(to:CMTime(seconds:30,preferredTimescale:600));try await Task.sleep(nanoseconds:2_000_000_000);report["seekPosition"]=player.currentTime().seconds;guard player.currentTime().seconds>=30 else{throw MusicError("Seek failed")};player.pause()
            }
            let data=try JSONSerialization.data(withJSONObject:report,options:[.prettyPrinted,.sortedKeys]);print(String(decoding:data,as:UTF8.self))
            if let i=args.firstIndex(of:"--report"),args.indices.contains(i+1){try data.write(to:URL(fileURLWithPath:args[i+1]),options:.atomic)}
        } catch {fputs("CHECK FAILED: \(error.localizedDescription)\n",stderr);exit(1)}
    }
    @MainActor static func selfTest() async throws {
        var count=0
        func expect(_ yes:@autoclosure ()->Bool,_ name:String) throws{guard yes() else{throw MusicError("Test failed: "+name)};count+=1;print("PASS "+name)}
        let y=try FolderLink.parse("https://disk.yandex.ru/d/exampleFolder123");try expect(y.provider == .yandex,"Yandex public folder")
        let g=try FolderLink.parse("https://drive.google.com/drive/u/0/folders/ABCDE_123?resourcekey=0-abc");try expect(g.resourceKey=="0-abc","Google resource key")
        for invalid in ["http://disk.yandex.ru/d/abcde","https://disk.yandex.ru.evil.test/d/abcde","https://user@disk.yandex.ru/d/abcde","https://disk.yandex.ru/i/abcde","https://drive.google.com/file/d/abcde/view","https://drive.google.com:8443/drive/folders/abcde"] {var rejected=false;do{_=try FolderLink.parse(invalid)}catch{rejected=true};try expect(rejected,"Reject invalid folder link")}
        let m=AudioNames.metadata("Artist - Name - Mix.flac");try expect(m.title=="Name - Mix" && m.artist=="Artist","Track metadata fallback")
        try expect(AudioNames.isAudio("Song.FLAC") && !AudioNames.isAudio("image.jpg"),"Audio extensions")
        let html="<title>Music - Google Drive</title><div class='flip-entries'><a href='https://drive.google.com/file/d/FILE123/view?resourcekey=0-aaa'><span>Artist &amp; Friend - Track.mp3</span></a><a href='https://drive.google.com/drive/folders/FOLDER1'>Album</a></div>"
        let page=try GooglePage.parse(html);try expect(page.name=="Music" && page.entries.count==2 && page.entries[0].name=="Artist & Friend - Track.mp3" && page.entries[1].folder,"Google nested folder HTML")
        let form="<form id='download-form' action='https://drive.usercontent.google.com/download'><input type='hidden' name='id' value='FILE123'><input name='confirm' value='t'></form>"
        let confirm=try GooglePage.confirmationURL(form);try expect(confirm.host=="drive.usercontent.google.com" && confirm.query?.contains("confirm=t")==true,"Google download confirmation")
        var blocked=false;do{_=try GooglePage.confirmationURL(form.replacingOccurrences(of:"drive.usercontent.google.com",with:"evil.test"))}catch{blocked=true};try expect(blocked,"Reject foreign download form")
        let jpeg=Data([255,216,255,217]),payload=Data([0])+Data("image/jpeg".utf8)+Data([0,3,0])+jpeg
        let frame=Data("APIC".utf8)+Data([0,0,0,UInt8(payload.count),0,0])+payload
        let tag=Data("ID3".utf8)+Data([3,0,0,0,0,0,UInt8(frame.count)])+frame
        try expect(ArtworkParser.image(in:tag)==jpeg,"ID3 artwork");try expect(ArtworkParser.image(in:tag.prefix(15))==nil,"Truncated artwork bounds")
        let directory=FileManager.default.temporaryDirectory.appendingPathComponent("cloud-music-selftest-"+UUID().uuidString);defer{try? FileManager.default.removeItem(at:directory)}
        let storage=LibraryStorage(directory:directory);var library=LibrarySnapshot();library.sources=[y];library.favorites=["test"]
        try await storage.save(library);let restored=try await storage.load();try expect(restored.sources.first?.id==y.id && restored.favorites.contains("test"),"Atomic library persistence")
        let order=PlaybackOrder(count:1000,current:234,shuffle:true)
        try expect(Set(order.indices).count==1000 && order.indices.first==234,"Full shuffle permutation")
        var visited=[234],current=234
        while let next=order.next(current){visited.append(next);current=next}
        try expect(visited.count==1000 && Set(visited).count==1000 && order.next(current)==nil,"Shuffle ends without repeating")
        try expect(order.next(current,wrap:true)==234 && order.previous(234)==current,"Repeat and previous follow same permutation")
        try expect(PlaybackOrder(count:0).next(0)==nil,"Empty queue")
        print("\(count) checks passed")
    }

    @MainActor static func fixture(_ base:String) async throws {
        var checks=0
        func expect(_ value:Bool,_ label:String) throws {guard value else{throw MusicError(label)};checks+=1;print("PASS "+label)}
        let client=CloudClient(yandexBase:base+"/v1/disk"),source=try FolderLink.parse("https://disk.yandex.ru/d/abcde")
        let contents=try await client.readFolder(source)
        try expect(contents.tracks.count==208 && Set(contents.tracks.map(\.id)).count==208,"Pagination and nested folders")
        try expect(contents.tracks.filter{$0.album=="Album"}.count==3,"Nested album context")
        let resolved=try await client.resolve(contents.tracks[0],source:contents.source,fresh:true)
        try expect(resolved.absoluteString=="https://example.invalid/audio.mp3","Fresh stream link")
        let prefix=try await HTTP.read(URL(string:base+"/ignore-range")!,limit:4096,range:4096)
        try expect(prefix.data.count==4096,"Server ignoring Range stays bounded")
        var rejected=false;do{_=try await HTTP.read(URL(string:base+"/ignore-range")!,limit:4096)}catch{rejected=true}
        try expect(rejected,"Oversized metadata response rejected")
        rejected=false;do{_=try await HTTP.read(URL(string:base+"/denied")!)}catch{rejected=true}
        try expect(rejected,"Permission error propagated")
        let folder=FileManager.default.temporaryDirectory.appendingPathComponent("cloud-music-download-check-"+UUID().uuidString)
        try FileManager.default.createDirectory(at:folder,withIntermediateDirectories:true);defer{try? FileManager.default.removeItem(at:folder)}
        let file=folder.appendingPathComponent("test.part"),job=DownloadJob(staging:file){_,_ in}
        let result=try await job.run(URL(string:base+"/download")!)
        let bytes=try Data(contentsOf:result)
        try expect(bytes.count==65536 && String(decoding:bytes.prefix(16),as:UTF8.self)=="CLOUDMUSIC-TEST-","Explicit download exact bytes")
        let partial=folder.appendingPathComponent("cancel.part"),cancelJob=DownloadJob(staging:partial){_,_ in}
        let task=Task{try await cancelJob.run(URL(string:base+"/slow")!)}
        try await Task.sleep(nanoseconds:90_000_000);task.cancel()
        var cancelled=false;do{_=try await task.value}catch{cancelled=true}
        try expect(cancelled && !FileManager.default.fileExists(atPath:partial.path),"Cancelled download leaves no partial copy")
        print("\(checks) network checks passed")
    }
}
