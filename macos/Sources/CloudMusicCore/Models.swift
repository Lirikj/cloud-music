import Foundation
import CryptoKit

public enum Provider: String, Codable, Sendable { case yandex, google, local, demo
    public var label: String { switch self { case .yandex: return "Яндекс Диск"; case .google: return "Google Drive"; case .local: return "На этом Mac"; case .demo: return "Демо · локально" } }
    public var symbol: String { switch self { case .yandex: return "cloud"; case .google: return "triangle"; case .local: return "laptopcomputer"; case .demo: return "waveform" } }
}
public struct MusicSource: Codable, Identifiable, Hashable, Sendable {
    public var id: String; public var name: String; public var provider: Provider; public var url: String
    public var remoteID: String = ""; public var resourceKey: String = ""; public var bookmark: Data?; public var updatedAt = Date()
    public init(id: String, name: String, provider: Provider, url: String, remoteID: String = "", resourceKey: String = "", bookmark: Data? = nil) {
        self.id=id; self.name=name; self.provider=provider; self.url=url; self.remoteID=remoteID; self.resourceKey=resourceKey; self.bookmark=bookmark
    }
}
public struct Track: Codable, Identifiable, Hashable, Sendable {
    public var id: String; public var sourceID: String; public var provider: Provider; public var remoteID: String
    public var filename: String; public var title: String; public var artist: String; public var album: String
    public var resourceKey: String = ""; public var size: Int64 = 0; public var duration: Double = 0; public var bookmark: Data?
    public init(id: String, sourceID: String, provider: Provider, remoteID: String, filename: String, title: String, artist: String, album: String, resourceKey: String = "", size: Int64 = 0, duration: Double = 0, bookmark: Data? = nil) {
        self.id=id; self.sourceID=sourceID; self.provider=provider; self.remoteID=remoteID; self.filename=filename; self.title=title; self.artist=artist; self.album=album; self.resourceKey=resourceKey; self.size=size; self.duration=duration; self.bookmark=bookmark
    }
    public var albumID: String { stableID("\(sourceID):\(artist.normalizedName):\(album.normalizedName)") }
}
public struct SavedDownload: Codable, Sendable {
    public var path: String; public var bytes: Int64; public var bookmark: Data?; public var createdAt: Date
    public init(path: String, bytes: Int64, bookmark: Data? = nil) { self.path=path; self.bytes=bytes; self.bookmark=bookmark; self.createdAt=Date() }
}
public struct LibrarySnapshot: Codable, Sendable {
    public var version=1; public var sources: [MusicSource]=[]; public var tracks: [Track]=[]; public var favorites: Set<String>=[]; public var downloads: [String:SavedDownload]=[:]
    public init() {}
}
public struct FolderContents: Sendable {
    public var source: MusicSource; public var tracks: [Track]; public var directLinks: [String:URL]
    public init(source: MusicSource, tracks: [Track], directLinks: [String:URL]=[:]) { self.source=source; self.tracks=tracks; self.directLinks=directLinks }
}
public struct MusicError: LocalizedError, Sendable {
    public let message: String
    public init(_ message: String) { self.message=message }
    public var errorDescription: String? { message }
}
public func stableID(_ value: String) -> String { SHA256.hash(data: Data(value.utf8)).map { String(format:"%02x",$0) }.joined() }
public extension String {
    var normalizedName: String { split(whereSeparator: \.isWhitespace).joined(separator: " ").folding(options: [.caseInsensitive,.diacriticInsensitive],locale: Locale(identifier:"en_US_POSIX")) }
    var trimmed: String { trimmingCharacters(in:.whitespacesAndNewlines) }
}
public enum AudioNames {
    public static let extensions: Set<String> = ["mp3","m4a","aac","flac","wav","wave","aif","aiff","caf","alac","mp4","ogg","opus","wma"]
    public static func isAudio(_ name: String, mime: String="") -> Bool { mime.hasPrefix("audio/") || extensions.contains((name as NSString).pathExtension.lowercased()) }
    public static func metadata(_ filename: String) -> (title:String,artist:String) {
        let text=(filename as NSString).deletingPathExtension.replacingOccurrences(of:"_",with:" ").trimmed
        let p=text.components(separatedBy:" - ")
        if p.count>=2, !p[0].trimmed.isEmpty, !p.dropFirst().joined(separator:" - ").trimmed.isEmpty { return (p.dropFirst().joined(separator:" - ").trimmed,p[0].trimmed) }
        return (text.isEmpty ? filename : text,"Неизвестный исполнитель")
    }
}
public enum FolderLink {
    public static func parse(_ value: String) throws -> MusicSource {
        guard let u=URLComponents(string:value.trimmed),u.scheme?.lowercased()=="https",u.user==nil,u.password==nil,u.port==nil || u.port==443,let host=u.host?.lowercased() else { throw MusicError("Вставь полную ссылку https:// на публичную папку.") }
        let path=u.path.trimmingCharacters(in:CharacterSet(charactersIn:"/")),parts=path.split(separator:"/").map(String.init)
        func valid(_ s:String)->Bool { s.range(of:"^[A-Za-z0-9_-]{5,200}$",options:.regularExpression) != nil }
        if ["disk.yandex.ru","disk.yandex.com","yadi.sk"].contains(host),parts.count==2,parts[0]=="d",valid(parts[1]) {
            return MusicSource(id:stableID("yandex:\(parts[1])"),name:"Музыка",provider:.yandex,url:"https://disk.yandex.ru/d/\(parts[1])",remoteID:parts[1])
        }
        if host=="drive.google.com",let folder=parts.last,valid(folder),path.range(of:"^drive/(?:u/[0-9]+/)?folders/[A-Za-z0-9_-]+$",options:.regularExpression) != nil {
            let key=u.queryItems?.first(where:{$0.name=="resourcekey"})?.value ?? ""
            guard key.isEmpty || key.range(of:"^[A-Za-z0-9_-]{1,200}$",options:.regularExpression) != nil else { throw MusicError("Некорректный ключ доступа Google Drive.") }
            return MusicSource(id:stableID("google:\(folder)"),name:"Музыка",provider:.google,url:"https://drive.google.com/drive/folders/\(folder)"+(key.isEmpty ? "" : "?resourcekey=\(key)"),remoteID:folder,resourceKey:key)
        }
        throw MusicError("Нужна ссылка на папку Яндекс Диска или Google Drive. Ссылки на отдельные файлы не подходят.")
    }
}
public actor LibraryStorage {
    public let directory: URL
    public init(directory: URL) { self.directory=directory }
    public func load() throws -> LibrarySnapshot {
        let url=directory.appendingPathComponent("library.json")
        guard FileManager.default.fileExists(atPath:url.path) else { return LibrarySnapshot() }
        return try JSONDecoder().decode(LibrarySnapshot.self,from:Data(contentsOf:url))
    }
    public func save(_ library: LibrarySnapshot) throws {
        try FileManager.default.createDirectory(at:directory,withIntermediateDirectories:true,attributes:[.posixPermissions:0o700])
        try JSONEncoder().encode(library).write(to:directory.appendingPathComponent("library.json"),options:.atomic)
        try FileManager.default.setAttributes([.posixPermissions:0o600],ofItemAtPath:directory.appendingPathComponent("library.json").path)
    }
}
