import Foundation
import AVFoundation

public enum FileAccess {
    public static func bookmark(_ url:URL) -> Data? { try? url.bookmarkData(options:.withSecurityScope,includingResourceValuesForKeys:nil,relativeTo:nil) }
    public static func resolve(_ data:Data?,fallback:URL) throws -> URL {
        guard let data else{return fallback}
        var stale=false
        return try URL(resolvingBookmarkData:data,options:[.withSecurityScope,.withoutUI],relativeTo:nil,bookmarkDataIsStale:&stale)
    }
}
public enum LocalFiles {
    public static func read(_ url:URL,sourceID:String,album:String,individualBookmark:Bool=false,provider:Provider = .local) async throws -> Track {
        let attrs=try url.resourceValues(forKeys:[.fileSizeKey,.isRegularFileKey])
        guard attrs.isRegularFile==true else{throw MusicError("Не удалось прочитать аудиофайл.")}
        let fallback=AudioNames.metadata(url.lastPathComponent),asset=AVURLAsset(url:url)
        var title=fallback.title,artist=fallback.artist,album=album,duration=0.0
        if let metadata=try? await asset.load(.commonMetadata) {
            for item in metadata {
                guard let value=try? await item.load(.stringValue),!value.trimmed.isEmpty else{continue}
                switch item.commonKey {case .commonKeyTitle:title=value;case .commonKeyArtist:artist=value;case .commonKeyAlbumName:album=value;default:break}
            }
        }
        if let time=try? await asset.load(.duration),time.seconds.isFinite{duration=time.seconds}
        return Track(id:stableID("\(sourceID):\(url.standardizedFileURL.path)"),sourceID:sourceID,provider:provider,remoteID:url.absoluteString,filename:url.lastPathComponent,title:title,artist:artist,album:album,size:Int64(attrs.fileSize ?? 0),duration:duration,bookmark:individualBookmark ? FileAccess.bookmark(url) : nil)
    }
    public static func folder(_ url:URL) async throws -> FolderContents {
        let access=url.startAccessingSecurityScopedResource();defer{if access{url.stopAccessingSecurityScopedResource()}}
        let source=MusicSource(id:stableID("local:\(url.standardizedFileURL.path)"),name:url.lastPathComponent,provider:.local,url:url.absoluteString,bookmark:FileAccess.bookmark(url))
        guard let enumerator=FileManager.default.enumerator(at:url,includingPropertiesForKeys:[.isRegularFileKey,.isDirectoryKey,.isSymbolicLinkKey],options:[.skipsHiddenFiles,.skipsPackageDescendants]) else{throw MusicError("Не удалось прочитать папку. Выбери её снова.")}
        var tracks:[Track]=[],folders=0
        while let file=enumerator.nextObject() as? URL {
            try Task.checkCancellation();let v=try file.resourceValues(forKeys:[.isRegularFileKey,.isDirectoryKey,.isSymbolicLinkKey])
            if v.isSymbolicLink==true {enumerator.skipDescendants();continue}
            if v.isDirectory==true {folders+=1;guard folders<=300,enumerator.level<=20 else{throw MusicError("Подключи папку поменьше: до 300 подпапок.")}}
            else if v.isRegularFile==true,AudioNames.isAudio(file.lastPathComponent) {
                guard tracks.count<20_000 else{throw MusicError("В папке больше 20 000 треков.")}
                tracks.append(try await read(file,sourceID:source.id,album:file.deletingLastPathComponent().lastPathComponent))
            }
        }
        return FolderContents(source:source,tracks:tracks)
    }
}
