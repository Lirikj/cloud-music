import AppKit
import CloudMusicCore
import ImageIO
import AVFoundation

@MainActor final class ArtworkStore:ObservableObject {
    @Published private(set) var revision=0
    @Published private(set) var cacheEpoch=0
    @Published var audioPriority=false {
        didSet {
            guard audioPriority != oldValue,audioPriority else{return}
            pending.values.forEach{$0.cancel()};pending.removeAll();pendingTokens.removeAll()
        }
    }
    private(set) var failureReason:[String:String]=[:]
    private let images=NSCache<NSString,NSImage>()
    private var pending:[String:Task<Void,Never>]=[:]
    private var attempted=Set<String>(),diskIDs=Set<String>()
    private var failures:[String:Int]=[:],retryAfter:[String:Date]=[:],pendingTokens:[String:UUID]=[:]
    private let directory:URL,cloud:CloudClient
    private struct Thumbnail:@unchecked Sendable {let cg:CGImage;let jpeg:Data?}

    init(directory:URL,cloud:CloudClient) {
        self.directory=directory;self.cloud=cloud
        images.totalCostLimit=4*1024*1024;images.countLimit=90
        try? FileManager.default.createDirectory(at:directory,withIntermediateDirectories:true)
        diskIDs=Set(((try? FileManager.default.contentsOfDirectory(at:directory,includingPropertiesForKeys:nil)) ?? []).filter{$0.pathExtension=="jpg"}.map{$0.deletingPathExtension().lastPathComponent})
    }
    // Rendering only looks in RAM. File reads, decoding and JPEG encoding run off the main actor.
    func image(_ track:Track)->NSImage? {images.object(forKey:track.id as NSString)}
    func request(_ track:Track,source:MusicSource?,localURL:URL?=nil) {
        let id=track.id
        guard image(track)==nil,pending[id]==nil,(diskIDs.contains(id) || !attempted.contains(id)),!audioPriority,pending.count<2,(retryAfter[id] ?? .distantPast)<=Date() else{return}
        let token=UUID(),attempt=failures[id] ?? 0,cached=diskIDs.contains(id),file=directory.appendingPathComponent(id+".jpg")
        pendingTokens[id]=token
        pending[id]=Task { [weak self,cloud] in
            defer {
                if self?.pendingTokens[id]==token {
                    self?.pending.removeValue(forKey:id);self?.pendingTokens.removeValue(forKey:id);self?.revision+=1
                }
            }
            do {
                if cached {
                    let thumbnail=await Task.detached(priority:.utility){()->Thumbnail? in
                        guard let data=try? Self.readPrefix(file,limit:4*1024*1024) else{return nil}
                        return Self.thumbnail(data,encode:false)
                    }.value
                    try Task.checkCancellation()
                    if let thumbnail {self?.install(thumbnail,id:id);return}
                    self?.diskIDs.remove(id)
                }
                let data:Data
                if let localURL {
                    let active=localURL.startAccessingSecurityScopedResource()
                    defer{if active{localURL.stopAccessingSecurityScopedResource()}}
                    data=try await Task.detached(priority:.utility){try Self.readPrefix(localURL,limit:4*1024*1024)}.value
                } else {
                    let url=try await cloud.resolve(track,source:source,fresh:attempt>0)
                    var bytes=try await HTTP.read(url,limit:32*1024,range:32*1024).data
                    for _ in 0..<5 {
                        guard let required=ArtworkParser.requiredPrefix(bytes) else{break}
                        try Task.checkCancellation();bytes=try await HTTP.read(url,limit:required,range:required).data
                    }
                    data=bytes
                }
                try Task.checkCancellation()
                var picture=await Task.detached(priority:.utility){ArtworkParser.image(in:data)}.value
                if picture==nil,let localURL{picture=await Self.localPicture(localURL)}
                guard let picture else {
                    self?.failureReason[id]="No embedded image in \(data.count) prefix bytes";self?.attempted.insert(id);return
                }
                let thumbnail=await Task.detached(priority:.utility){Self.thumbnail(picture,encode:true)}.value
                try Task.checkCancellation()
                guard let thumbnail else{self?.failureReason[id]="ImageIO rejected embedded image";self?.attempted.insert(id);return}
                if let jpeg=thumbnail.jpeg,let folder=self?.directory {
                    let retained=await Task.detached(priority:.utility){()->Set<String> in
                        try? jpeg.write(to:file,options:.atomic);return Self.trimDisk(folder)
                    }.value
                    try Task.checkCancellation();self?.diskIDs=retained
                }
                self?.install(thumbnail,id:id)
            } catch {
                guard !Task.isCancelled,let self else{return}
                self.failureReason[id]=error.localizedDescription;self.failures[id]=attempt+1
                if attempt<2 {
                    let delay=attempt==0 ? 2.0:6.0,next=Date().addingTimeInterval(attempt==0 ? 2:6)
                    self.retryAfter[id]=next
                    Task { [weak self] in
                        try? await Task.sleep(nanoseconds:UInt64(delay*1_000_000_000))
                        guard let self,self.retryAfter[id]==next else{return};self.revision+=1
                    }
                } else{self.attempted.insert(id)}
            }
        }
    }
    private func install(_ thumbnail:Thumbnail,id:String) {
        images.setObject(NSImage(cgImage:thumbnail.cg,size:.zero),forKey:id as NSString,cost:thumbnail.cg.bytesPerRow*thumbnail.cg.height)
        failures.removeValue(forKey:id);retryAfter.removeValue(forKey:id);failureReason.removeValue(forKey:id);attempted.insert(id)
    }
    private nonisolated static func readPrefix(_ file:URL,limit:Int) throws -> Data {
        let handle=try FileHandle(forReadingFrom:file);defer{try? handle.close()}
        return try handle.read(upToCount:limit) ?? Data()
    }
    private nonisolated static func thumbnail(_ data:Data,encode:Bool)->Thumbnail? {
        autoreleasepool {
            guard let source=CGImageSourceCreateWithData(data as CFData,nil),
                  let cg=CGImageSourceCreateThumbnailAtIndex(source,0,[kCGImageSourceCreateThumbnailFromImageAlways:true,kCGImageSourceThumbnailMaxPixelSize:384,kCGImageSourceCreateThumbnailWithTransform:true] as CFDictionary) else{return nil}
            let jpeg=encode ? NSBitmapImageRep(cgImage:cg).representation(using:.jpeg,properties:[.compressionFactor:0.8]):nil
            return Thumbnail(cg:cg,jpeg:jpeg)
        }
    }
    private nonisolated static func localPicture(_ url:URL) async -> Data? {
        guard let metadata=try? await AVURLAsset(url:url).load(.commonMetadata),let item=metadata.first(where:{$0.commonKey == .commonKeyArtwork}),let bytes=try? await item.load(.dataValue),bytes.count<=4*1024*1024 else{return nil}
        return bytes
    }
    func clear() {
        pending.values.forEach{$0.cancel()};pending.removeAll();pendingTokens.removeAll();attempted.removeAll();diskIDs.removeAll()
        failures.removeAll();retryAfter.removeAll();failureReason.removeAll();images.removeAllObjects()
        for file in (try? FileManager.default.contentsOfDirectory(at:directory,includingPropertiesForKeys:nil)) ?? [] where file.pathExtension=="jpg" {try? FileManager.default.removeItem(at:file)}
        cacheEpoch+=1;revision+=1
    }
    private nonisolated static func trimDisk(_ directory:URL)->Set<String> {
        let files=((try? FileManager.default.contentsOfDirectory(at:directory,includingPropertiesForKeys:[.fileSizeKey,.contentModificationDateKey])) ?? []).filter{$0.pathExtension=="jpg"}
        let entries=files.map{url -> (URL,Int,Date) in let values=try? url.resourceValues(forKeys:[.fileSizeKey,.contentModificationDateKey]);return (url,values?.fileSize ?? 0,values?.contentModificationDate ?? .distantPast)}.sorted{$0.2<$1.2}
        var bytes=entries.reduce(0){$0+$1.1},retained=Set(files.map{$0.deletingPathExtension().lastPathComponent})
        for (file,size,_) in entries where bytes>4*1024*1024 {
            if (try? FileManager.default.removeItem(at:file)) != nil {bytes-=size;retained.remove(file.deletingPathExtension().lastPathComponent)}
        }
        return retained
    }
}
