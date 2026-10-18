import AppKit
import Combine
import CloudMusicCore

struct AlbumGroup:Identifiable {let id:String;let name:String;let artist:String;let tracks:[Track];var first:Track{tracks[0]}}
struct ArtistGroup:Identifiable {let id:String;let name:String;let tracks:[Track];var first:Track{tracks[0]}}
struct DownloadState {var label:String;var fraction:Double?;var active:Bool}
@MainActor final class LibraryModel:ObservableObject {
    @Published private(set) var library=LibrarySnapshot(){didSet{rebuildIndex(previous:oldValue)}}
    @Published private(set) var isReady=false
    @Published var selected="library";@Published var query="";@Published var artist:String?;@Published var albumID:String?
    @Published var showPlayer=false;@Published var showQueue=true;@Published var grid=true;@Published var showConnect=false
    @Published var busy=false;@Published var busyLabel="";@Published var alert:String?;@Published var status:String?
    @Published var appearance:String {didSet{defaults.set(appearance,forKey:"appearance")}}
    @Published private(set) var downloadStates:[String:DownloadState]=[:]
    let player:PlayerModel;let artwork:ArtworkStore;let cloud:CloudClient;let storage:LibraryStorage;let directory:URL
    private var storageReady=false
    private var sortedTracks:[Track]=[];private var searchIndex:[String:String]=[:];private var albumKeys:[String:String]=[:];private var artistKeys:[String:String]=[:];private var sourceIndex:[String:MusicSource]=[:];private var downloadedIDs=Set<String>();private var indexVersion=0
    private struct ListingKey:Equatable {let version:Int;let selected:String;let query:String;let artist:String?;let album:String?}
    private struct Listing {let key:ListingKey;let tracks:[Track];let albums:[AlbumGroup];let artists:[ArtistGroup]}
    private var listingCache:Listing?
    private var lastDownloadProgress=Date.distantPast
    private let defaults:UserDefaults;private var scopes:[URL]=[];private var importTask:Task<Void,Never>?;private var saveTask:Task<Void,Never>?
    private var pendingDownloads:[Track]=[];private var downloadWorker:Task<Void,Never>?;private var activeDownload:DownloadJob?;private var activeDownloadID:String?;private var cancelledDownloads=Set<String>()
    init(){
        let args=CommandLine.arguments
        if let i=args.firstIndex(of:"--library-directory"),args.indices.contains(i+1){directory=URL(fileURLWithPath:args[i+1],isDirectory:true)}
        else{directory=FileManager.default.urls(for:.applicationSupportDirectory,in:.userDomainMask)[0].appendingPathComponent("Cloud Music",isDirectory:true)}
        defaults=UserDefaults(suiteName:"app.cloudmusic.macos."+stableID(directory.path).prefix(12)) ?? .standard
        appearance=defaults.string(forKey:"appearance") ?? "dark"
        cloud=CloudClient();storage=LibraryStorage(directory:directory);player=PlayerModel();artwork=ArtworkStore(directory:directory.appendingPathComponent("Artwork",isDirectory:true),cloud:cloud)
        player.resolve={ [weak self] track,fresh in guard let self else{throw CancellationError()};return try await self.playbackURL(track,fresh:fresh) }
        player.priority={ [weak artwork] flag in if artwork?.audioPriority != flag{artwork?.audioPriority=flag} }
        Task{do{library=try await storage.load();storageReady=true;restoreScopes();cleanPartialDownloads()}catch{alert="Не удалось открыть библиотеку. Данные не изменены: \(error.localizedDescription)"};isReady=true}
    }
    private func rebuildIndex(previous:LibrarySnapshot){
        if previous.tracks != library.tracks {
            sortedTracks=library.tracks.sorted{a,b in let r=a.title.localizedStandardCompare(b.title);return r == .orderedSame ? a.id<b.id:r == .orderedAscending}
            searchIndex=Dictionary(uniqueKeysWithValues:sortedTracks.map{($0.id,"\($0.title) \($0.artist) \($0.album)".normalizedName)})
            albumKeys=Dictionary(uniqueKeysWithValues:sortedTracks.map{($0.id,$0.albumID)})
            artistKeys=Dictionary(uniqueKeysWithValues:sortedTracks.map{($0.id,$0.artist.normalizedName)})
        }
        sourceIndex=Dictionary(uniqueKeysWithValues:library.sources.map{($0.id,$0)})
        downloadedIDs=Set(library.downloads.compactMap{FileManager.default.fileExists(atPath:$0.value.path) ? $0.key:nil})
        indexVersion+=1;listingCache=nil
    }
    func refreshFileAvailability(){
        let available=Set(library.downloads.compactMap{FileManager.default.fileExists(atPath:$0.value.path) ? $0.key:nil})
        guard available != downloadedIDs else{return};objectWillChange.send();downloadedIDs=available;indexVersion+=1;listingCache=nil
    }
    var tracks:[Track]{sortedTracks}
    private var listing:Listing {
        let key=ListingKey(version:indexVersion,selected:selected,query:query.normalizedName,artist:artist?.normalizedName,album:albumID)
        if let cached=listingCache,cached.key==key{return cached}
        var list=sortedTracks
        if selected=="favorites"{list=list.filter{library.favorites.contains($0.id)}}
        else if selected=="local"{list=list.filter{$0.provider == .local || $0.provider == .demo || downloaded($0)}}
        else if selected=="downloads"{list=list.filter{downloaded($0)}}
        else if selected.hasPrefix("source:"){let id=String(selected.dropFirst(7));list=list.filter{$0.sourceID==id}}
        if let artist=key.artist{list=list.filter{artistKeys[$0.id]==artist}}
        if let album=key.album{list=list.filter{albumKeys[$0.id]==album}}
        if !key.query.isEmpty{list=list.filter{searchIndex[$0.id]?.contains(key.query)==true}}
        let albums=Dictionary(grouping:list,by:{albumKeys[$0.id] ?? $0.id}).map{AlbumGroup(id:$0.key,name:$0.value[0].album,artist:$0.value[0].artist,tracks:$0.value)}.sorted{a,b in let comparison=a.name.localizedStandardCompare(b.name);if comparison != .orderedSame{return comparison == .orderedAscending};let artistOrder=a.artist.localizedStandardCompare(b.artist);return artistOrder == .orderedSame ? a.id<b.id:artistOrder == .orderedAscending}
        let artists=Dictionary(grouping:list,by:{artistKeys[$0.id] ?? $0.id}).map{ArtistGroup(id:$0.key,name:$0.value[0].artist,tracks:$0.value)}.sorted{$0.name.localizedStandardCompare($1.name) == .orderedAscending}
        let result=Listing(key:key,tracks:list,albums:albums,artists:artists);listingCache=result;return result
    }
    var visibleTracks:[Track]{listing.tracks}
    var albums:[AlbumGroup]{listing.albums}
    var artists:[ArtistGroup]{listing.artists}
    func albumTitle(_ album:AlbumGroup)->String{if album.name==source(album.first)?.name && album.artist != "Неизвестный исполнитель"{return album.artist};return album.name}
    var pageTitle:String {
        if !query.trimmed.isEmpty{return "Поиск"};if let artist{return artist};if let id=albumID{return library.tracks.first{$0.albumID==id}?.album ?? "Альбом"}
        switch selected {case "favorites":return "Избранное";case "artists":return "Исполнители";case "local":return "На этом Mac";case "downloads":return "Скачанные треки";default:if selected.hasPrefix("source:"),let source=library.sources.first(where:{"source:"+$0.id==selected}){return source.name};return "Моя музыка"}
    }
    func navigate(_ selection:String){selected=selection;query="";artist=nil;albumID=nil;showPlayer=false}
    func openArtist(_ name:String){navigate("artists");artist=name}
    func openAlbum(_ album:AlbumGroup){artist=nil;albumID=album.id;showPlayer=false}
    func play(_ track:Track){player.play(track,in:visibleTracks)}
    func toggleFavorite(_ track:Track){if library.favorites.contains(track.id){library.favorites.remove(track.id)}else{library.favorites.insert(track.id)};save()}
    func source(_ track:Track)->MusicSource?{sourceIndex[track.sourceID]}
    func downloaded(_ track:Track)->Bool{downloadedIDs.contains(track.id)}
    func localURL(_ track:Track)->URL?{
        if let d=library.downloads[track.id],FileManager.default.fileExists(atPath:d.path){return (try? FileAccess.resolve(d.bookmark,fallback:URL(fileURLWithPath:d.path))) ?? URL(fileURLWithPath:d.path)}
        guard track.provider == .local || track.provider == .demo,let original=URL(string:track.remoteID),original.isFileURL else{return nil}
        if let data=track.bookmark{return try? FileAccess.resolve(data,fallback:original)}
        if let source=source(track),let originalRoot=URL(string:source.url),let root=try? FileAccess.resolve(source.bookmark,fallback:originalRoot),original.path.hasPrefix(originalRoot.path+"/") {return root.appendingPathComponent(String(original.path.dropFirst(originalRoot.path.count+1)))}
        return original
    }
    private func playbackURL(_ track:Track,fresh:Bool) async throws -> URL {
        if let local=localURL(track){guard FileManager.default.fileExists(atPath:local.path) else{throw MusicError("Файл перемещён или удалён. Выбери папку заново.")};return local}
        if track.provider == .local || track.provider == .demo{throw MusicError("Нет доступа к локальному файлу. Выбери его заново.")}
        return try await cloud.resolve(track,source:source(track),fresh:fresh)
    }
    func connect(_ text:String){
        do{let source=try FolderLink.parse(text);showConnect=false;beginImport("Подключение папки…"){try await self.cloud.readFolder(source)}}catch{alert=error.localizedDescription}
    }
    private func beginImport(_ label:String,operation:@escaping () async throws -> FolderContents){
        importTask?.cancel();busy=true;busyLabel=label
        importTask=Task{
            do{let contents=try await operation();try Task.checkCancellation();merge(contents);status="Папка «\(contents.source.name)» подключена · \(contents.tracks.count) треков";busy=false}
            catch{guard !Task.isCancelled else{return};busy=false;alert=error.localizedDescription}
        }
    }
    func cancelImport(){importTask?.cancel();busy=false}
    private func merge(_ content:FolderContents){
        let ids=Set(content.tracks.map(\.id));library.sources.removeAll{$0.id==content.source.id};library.sources.append(content.source)
        library.tracks.removeAll{$0.sourceID==content.source.id && (!library.downloads.keys.contains($0.id) || ids.contains($0.id))}
        let remaining=Set(library.tracks.map(\.id));library.tracks.append(contentsOf:content.tracks.filter{!remaining.contains($0.id)})
        restoreScopes();navigate("library");save()
    }
    func refresh(_ source:MusicSource){
        if source.provider == .local,source.id=="local-selected-files"{
            beginImport("Обновление локальных файлов…"){
                var updated:[Track]=[]
                for old in self.library.tracks.filter({$0.sourceID==source.id}){
                    guard let url=self.localURL(old) else{throw MusicError("Выбери локальный файл снова.")}
                    let active=url.startAccessingSecurityScopedResource();defer{if active{url.stopAccessingSecurityScopedResource()}}
                    var track=try await LocalFiles.read(url,sourceID:source.id,album:old.album,individualBookmark:true);track.id=old.id;updated.append(track)
                }
                return FolderContents(source:source,tracks:updated)
            }
        }
        else if source.provider == .local,let u=URL(string:source.url),u.isFileURL{beginImport("Обновление локальной папки…"){let url=try FileAccess.resolve(source.bookmark,fallback:u);return try await LocalFiles.folder(url)}}
        else if source.provider == .yandex || source.provider == .google{beginImport("Обновление «\(source.name)»…"){try await self.cloud.readFolder(source)}}
    }
    func remove(_ source:MusicSource){
        for track in library.tracks where track.sourceID==source.id && downloadStates[track.id]?.active==true {cancelDownload(track)}
        let removing=Set(library.tracks.filter{$0.sourceID==source.id && !downloaded($0)}.map(\.id));if let current=player.current,removing.contains(current.id){player.stop()}
        library.sources.removeAll{$0.id==source.id};library.tracks.removeAll{removing.contains($0.id)};library.favorites.subtract(removing);restoreScopes();navigate("library");save()
    }
    func chooseLocal(){
        let panel=NSOpenPanel();panel.title="Выбери музыку";panel.prompt="Добавить";panel.canChooseDirectories=true;panel.canChooseFiles=true;panel.allowsMultipleSelection=true;panel.canCreateDirectories=false
        guard panel.runModal() == .OK else{return};importURLs(panel.urls)
    }
    func importURLs(_ urls:[URL]){
        importTask?.cancel();busy=true;busyLabel="Чтение файлов на Mac…"
        importTask=Task{
            do {
                var files:[URL]=[]
                for url in urls{try Task.checkCancellation();if (try? url.resourceValues(forKeys:[.isDirectoryKey]).isDirectory)==true{let c=try await LocalFiles.folder(url);try Task.checkCancellation();merge(c)}else if AudioNames.isAudio(url.lastPathComponent){files.append(url)}}
                if !files.isEmpty {
                    let source=MusicSource(id:"local-selected-files",name:"Файлы на Mac",provider:.local,url:"local://files");var tracks:[Track]=[]
                    for url in files{let scope=url.startAccessingSecurityScopedResource();defer{if scope{url.stopAccessingSecurityScopedResource()}};tracks.append(try await LocalFiles.read(url,sourceID:source.id,album:"Файлы на Mac",individualBookmark:true))}
                    let existing=library.tracks.filter{$0.sourceID==source.id && !Set(tracks.map(\.id)).contains($0.id)};merge(FolderContents(source:source,tracks:existing+tracks))
                }
                busy=false;status="Локальная музыка добавлена"
            }catch{guard !Task.isCancelled else{return};busy=false;alert=error.localizedDescription}
        }
    }
    func addDemo(){
        guard let resource=Bundle.main.resourceURL?.appendingPathComponent("Demo"),FileManager.default.fileExists(atPath:resource.path) else{return}
        beginImport("Добавление демо…"){
            var contents=try await LocalFiles.folder(resource);contents.source=MusicSource(id:"cloud-music-demo",name:"Демо · локально",provider:.demo,url:resource.absoluteString)
            contents.tracks=contents.tracks.map{t in var t=t;t.id=stableID("demo:"+t.filename);t.sourceID=contents.source.id;t.provider = .demo;t.artist="Cloud Music";t.album="Демо · оригинальные звуки";return t};return contents
        }
    }
    private func restoreScopes(){scopes.forEach{$0.stopAccessingSecurityScopedResource()};scopes=[];for source in library.sources where source.provider == .local {if let u=URL(string:source.url),u.isFileURL,let resolved=try? FileAccess.resolve(source.bookmark,fallback:u),resolved.startAccessingSecurityScopedResource(){scopes.append(resolved)}}}
    private func cleanPartialDownloads(){
        guard let data=defaults.data(forKey:"downloadDirectory"),let root=try? FileAccess.resolve(data,fallback:directory) else{return}
        let active=root.startAccessingSecurityScopedResource();defer{if active{root.stopAccessingSecurityScopedResource()}}
        let folder=root.appendingPathComponent("Cloud Music",isDirectory:true)
        for url in (try? FileManager.default.contentsOfDirectory(at:folder,includingPropertiesForKeys:[.isRegularFileKey])) ?? [] {
            if url.lastPathComponent.range(of:"^\\.cloud-music-[A-Fa-f0-9-]{36}\\.part$",options:.regularExpression) != nil,(try? url.resourceValues(forKeys:[.isRegularFileKey]).isRegularFile)==true{try? FileManager.default.removeItem(at:url)}
        }
    }
    func save(){guard storageReady else{return};let snapshot=library;saveTask?.cancel();saveTask=Task{do{try await Task.sleep(nanoseconds:150_000_000);try Task.checkCancellation();try await storage.save(snapshot)}catch{if !Task.isCancelled{alert="Не удалось сохранить библиотеку: \(error.localizedDescription)"}}}}
    func flush() async {guard storageReady else{return};do{saveTask?.cancel();try await storage.save(library)}catch{alert=error.localizedDescription}}
    func download(_ track:Track){
        guard track.provider == .yandex || track.provider == .google,!downloaded(track),downloadStates[track.id]?.active != true else{return}
        guard downloadDirectory() != nil else{return};cancelledDownloads.remove(track.id);pendingDownloads.append(track);downloadStates[track.id]=DownloadState(label:"В очереди",fraction:nil,active:true)
        if downloadWorker==nil{downloadWorker=Task{await drainDownloads();downloadWorker=nil}}
    }
    private func downloadDirectory()->URL? {
        if let data=defaults.data(forKey:"downloadDirectory"),let u=try? FileAccess.resolve(data,fallback:FileManager.default.urls(for:.downloadsDirectory,in:.userDomainMask)[0]){return u}
        let panel=NSOpenPanel();panel.title="Куда сохранять музыку?";panel.message="Только выбранные тобой треки будут занимать место на диске.";panel.prompt="Выбрать папку";panel.canChooseDirectories=true;panel.canChooseFiles=false;panel.canCreateDirectories=true;panel.directoryURL=FileManager.default.urls(for:.downloadsDirectory,in:.userDomainMask).first
        guard panel.runModal() == .OK,let u=panel.url else{return nil};if let data=FileAccess.bookmark(u){defaults.set(data,forKey:"downloadDirectory")};return u
    }
    private func drainDownloads() async {
        while !pendingDownloads.isEmpty {
            let track=pendingDownloads.removeFirst();if cancelledDownloads.contains(track.id){continue}
            guard let root=downloadDirectory() else{downloadStates[track.id]=DownloadState(label:"Не выбрана папка",active:false);continue}
            let scoped=root.startAccessingSecurityScopedResource();defer{if scoped{root.stopAccessingSecurityScopedResource()}}
            let folder=root.appendingPathComponent("Cloud Music",isDirectory:true)
            do{
                try FileManager.default.createDirectory(at:folder,withIntermediateDirectories:true)
                let staging=folder.appendingPathComponent(".cloud-music-\(UUID().uuidString).part");defer{try? FileManager.default.removeItem(at:staging)}
                var completed:URL?
                for attempt in 0..<2 {
                    if cancelledDownloads.contains(track.id){break}
                    downloadStates[track.id]=DownloadState(label:attempt==0 ? "Скачивание…":"Повторное подключение…",fraction:nil,active:true)
                    do {
                        let url=try await cloud.resolve(track,source:source(track),fresh:attempt>0);if cancelledDownloads.contains(track.id){break}
                        lastDownloadProgress = .distantPast
                        let job=DownloadJob(staging:staging){[weak self] bytes,total in Task{@MainActor [weak self] in guard let self,!self.cancelledDownloads.contains(track.id),Date().timeIntervalSince(self.lastDownloadProgress)>=0.15 || (total>0 && bytes>=total) else{return};self.lastDownloadProgress=Date();self.downloadStates[track.id]=DownloadState(label:ByteCountFormatter.string(fromByteCount:bytes,countStyle:.file),fraction:total>0 ? min(1,Double(bytes)/Double(total)):nil,active:true)}}
                        activeDownload=job;activeDownloadID=track.id;completed=try await job.run(url);break
                    }catch{if cancelledDownloads.contains(track.id){break};if attempt==1{throw error}}
                }
                activeDownload=nil;activeDownloadID=nil
                guard let completed,!cancelledDownloads.contains(track.id) else{downloadStates[track.id]=DownloadState(label:"Отменено",active:false);continue}
                let cleaned=String(track.filename.replacingOccurrences(of:"[/\\\\:\\x00-\\x1f]",with:"_",options:.regularExpression).prefix(170))
                var destination=folder.appendingPathComponent(String(track.id.prefix(8))+"-"+cleaned)
                if FileManager.default.fileExists(atPath:destination.path){destination=folder.appendingPathComponent(String(UUID().uuidString.prefix(8))+"-"+cleaned)}
                try FileManager.default.moveItem(at:completed,to:destination)
                let bytes=(try destination.resourceValues(forKeys:[.fileSizeKey]).fileSize) ?? 0;library.downloads[track.id]=SavedDownload(path:destination.path,bytes:Int64(bytes),bookmark:FileAccess.bookmark(destination));downloadStates[track.id]=DownloadState(label:"Скачано",fraction:1,active:false);save()
            }catch{activeDownload=nil;activeDownloadID=nil;downloadStates[track.id]=DownloadState(label:"Не удалось скачать: \(error.localizedDescription)",active:false)}
        }
    }
    func cancelDownload(_ track:Track){cancelledDownloads.insert(track.id);pendingDownloads.removeAll{$0.id==track.id};if activeDownloadID==track.id{activeDownload?.cancel()};downloadStates[track.id]=DownloadState(label:"Отменено",active:false)}
    func removeDownload(_ track:Track){
        guard let record=library.downloads[track.id] else{return}
        do{if player.current?.id==track.id{player.stop()};let u=(try? FileAccess.resolve(record.bookmark,fallback:URL(fileURLWithPath:record.path))) ?? URL(fileURLWithPath:record.path);let active=u.startAccessingSecurityScopedResource();defer{if active{u.stopAccessingSecurityScopedResource()}};if FileManager.default.fileExists(atPath:u.path){try FileManager.default.removeItem(at:u)};library.downloads.removeValue(forKey:track.id);downloadStates.removeValue(forKey:track.id);if source(track)==nil{library.tracks.removeAll{$0.id==track.id};library.favorites.remove(track.id)};save()}
        catch{alert="Не удалось удалить скачанную копию: \(error.localizedDescription)"}
    }
    func reveal(_ track:Track){if let url=localURL(track){NSWorkspace.shared.activateFileViewerSelecting([url])}}
}
