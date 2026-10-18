import Foundation

public struct GoogleEntry: Equatable, Sendable { public var id:String; public var name:String; public var folder:Bool; public var key:String }
public enum GooglePage {
    static func matches(_ pattern:String,_ text:String)->[[String]] {
        guard let r=try? NSRegularExpression(pattern:pattern,options:[.caseInsensitive,.dotMatchesLineSeparators]) else{return []}
        let ns=text as NSString
        return r.matches(in:text,range:NSRange(location:0,length:ns.length)).map { m in (0..<m.numberOfRanges).map { m.range(at:$0).location == NSNotFound ? "" : ns.substring(with:m.range(at:$0)) } }
    }
    static func plain(_ s:String)->String {
        let text=s.replacingOccurrences(of:"<[^>]+>",with:"",options:.regularExpression)
        var result=text.replacingOccurrences(of:"&amp;",with:"&").replacingOccurrences(of:"&quot;",with:"\"").replacingOccurrences(of:"&#39;",with:"'").replacingOccurrences(of:"&lt;",with:"<").replacingOccurrences(of:"&gt;",with:">").replacingOccurrences(of:"&nbsp;",with:" ")
        for m in matches("&#(x[0-9a-f]+|[0-9]+);",result).reversed() { let code=m[1].lowercased();if let n=UInt32(code.hasPrefix("x") ? String(code.dropFirst()) : code,radix:code.hasPrefix("x") ? 16 : 10),let scalar=UnicodeScalar(n){result=result.replacingOccurrences(of:m[0],with:String(scalar))} }
        return result.trimmed
    }
    public static func parse(_ html:String) throws -> (name:String,entries:[GoogleEntry]) {
        let title=plain(matches("<title[^>]*>(.*?)</title>",html).first?[1] ?? "").replacingOccurrences(of:" - Google Drive",with:"")
        if title.lowercased().contains("sign in") || html.contains("accounts.google.com/ServiceLogin") { throw MusicError("Эта папка требует входа в Google. Открой доступ по ссылке.") }
        var entries:[GoogleEntry]=[];var visited=Set<String>()
        for m in matches("<a\\b[^>]*href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</a>",html) {
            guard let u=URLComponents(string:plain(m[1])),u.host=="drive.google.com" else {continue}
            let file=matches("^/file/d/([A-Za-z0-9_-]+)/view/?$",u.path).first
            let folder=matches("^/drive/(?:u/[0-9]+/)?folders/([A-Za-z0-9_-]+)/?$",u.path).first
            guard let id=(file ?? folder)?[1],visited.insert(id).inserted else{continue}
            entries.append(GoogleEntry(id:id,name:plain(m[2]),folder:folder != nil,key:u.queryItems?.first(where:{$0.name=="resourcekey"})?.value ?? ""))
        }
        guard !title.isEmpty, !entries.isEmpty || html.contains("folder-empty") || html.contains("flip-entries") || html.contains("flip-empty") else { throw MusicError("Google Drive не открыл список файлов. Проверь публичный доступ к папке.") }
        return (title,entries)
    }
    public static func confirmationURL(_ html:String) throws -> URL {
        guard let form=matches("<form\\b([^>]*)>(.*?)</form>",html).first(where:{$0[1].contains("download-form")}),let action=matches("action\\s*=\\s*[\"']([^\"']+)[\"']",form[1]).first?[1],var u=URLComponents(string:plain(action)),u.scheme=="https",["drive.google.com","drive.usercontent.google.com"].contains(u.host ?? "") else {throw MusicError("Google Drive ограничил скачивание или требует входа.")}
        var query=u.queryItems ?? []
        for input in matches("<input\\b([^>]*)>",form[2]) {
            let attrs=Dictionary(matches("([a-z-]+)\\s*=\\s*[\"']([^\"']*)[\"']",input[1]).map{($0[1].lowercased(),plain($0[2]))},uniquingKeysWith:{$1})
            if let name=attrs["name"] { query.append(URLQueryItem(name:name,value:attrs["value"] ?? "")) }
        }
        u.queryItems=query;guard let url=u.url else{throw MusicError("Некорректная ссылка Google Drive.")};return url
    }
}
public actor CloudClient {
    private struct Link { var url:URL;var expires:Date }
    private var links:[String:Link]=[:]
    private let yandexBase:String
    public init(yandexBase:String="https://cloud-api.yandex.net/v1/disk") { self.yandexBase=yandexBase }
    private func url(_ base:String,_ params:[String:String])->URL {
        var c=URLComponents(string:base)!;c.queryItems=params.sorted(by:{$0.key<$1.key}).map{URLQueryItem(name:$0.key,value:$0.value)};return c.url!
    }
    private func json(_ u:URL) async throws -> [String:Any] { let r=try await HTTP.read(u);guard let d=try JSONSerialization.jsonObject(with:r.data) as? [String:Any] else{throw MusicError("Некорректный ответ облака.")};return d }
    public func readFolder(_ source:MusicSource) async throws -> FolderContents {
        switch source.provider {
        case .yandex: return try await readYandex(source)
        case .google: return try await readGoogle(source)
        default: throw MusicError("Выбери локальные файлы через системный диалог.")
        }
    }
    private func readYandex(_ source:MusicSource) async throws -> FolderContents {
        var source=source, tracks:[Track]=[], direct:[String:URL]=[:], visited=Set<String>()
        var todo:[(path:String?,album:String,depth:Int)]=[(nil,"Музыка",0)]
        while !todo.isEmpty {
            try Task.checkCancellation();let node=todo.removeFirst();let path=node.path ?? ""
            guard visited.insert(path).inserted else{continue}
            guard node.depth<=20,visited.count<=300 else{throw MusicError("Подключи папку поменьше: до 300 подпапок и 20 уровней.")}
            var offset=0
            while true {
                try Task.checkCancellation();var p=["public_key":source.url,"limit":"200","offset":String(offset)];if let path=node.path{p["path"]=path}
                let page=try await json(url(yandexBase+"/public/resources",p))
                guard page["type"] as? String=="dir" else{throw MusicError("Нужна ссылка на папку, а не на файл.")}
                if node.path==nil { source.name=page["name"] as? String ?? "Музыка" }
                guard let embedded=page["_embedded"] as? [String:Any],let items=embedded["items"] as? [[String:Any]] else{throw MusicError("Облако вернуло неполный список файлов.")}
                for item in items {
                    guard let name=item["name"] as? String,let path=item["path"] as? String,!path.isEmpty else{continue}
                    if item["type"] as? String=="dir" { guard todo.count+visited.count<300 else{throw MusicError("Подключи папку с количеством подпапок до 300.")};todo.append((path,name,node.depth+1)) }
                    else if AudioNames.isAudio(name,mime:item["mime_type"] as? String ?? "") {
                        guard tracks.count<20_000 else{throw MusicError("В папке больше 20 000 треков. Выбери отдельную коллекцию.")}
                        let m=AudioNames.metadata(name),id=stableID("\(source.id):\(path)")
                        tracks.append(Track(id:id,sourceID:source.id,provider:.yandex,remoteID:path,filename:name,title:m.title,artist:m.artist,album:node.path==nil ? source.name : node.album,size:(item["size"] as? NSNumber)?.int64Value ?? 0))
                        if direct.count<512,let text=item["file"] as? String,text.count<=4096,let u=URL(string:text),u.scheme=="https" {direct[id]=u;links[id]=Link(url:u,expires:Date().addingTimeInterval(240))}
                    }
                }
                offset+=items.count
                if items.isEmpty || offset>=((embedded["total"] as? Int) ?? offset) {break}
            }
        }
        source.updatedAt=Date();trimLinks();return FolderContents(source:source,tracks:tracks,directLinks:direct)
    }
    private func readGoogle(_ source:MusicSource) async throws -> FolderContents {
        var source=source,tracks:[Track]=[],visited=Set<String>(),todo=[(id:source.remoteID,key:source.resourceKey,album:"Музыка",depth:0)]
        while !todo.isEmpty {
            try Task.checkCancellation();let node=todo.removeFirst();guard visited.insert(node.id).inserted else{continue}
            guard node.depth<=20,visited.count<=300 else{throw MusicError("Слишком много подпапок. Подключи папку поменьше.")}
            var p=["id":node.id];if !node.key.isEmpty {p["resourcekey"]=node.key}
            let r=try await HTTP.read(url("https://drive.google.com/embeddedfolderview",p));let page=try GooglePage.parse(String(decoding:r.data,as:UTF8.self))
            if node.depth==0 {source.name=page.name}
            for e in page.entries {
                if e.folder { guard todo.count+visited.count<300 else{throw MusicError("Слишком много подпапок.")};todo.append((e.id,e.key,e.name,node.depth+1)) }
                else if AudioNames.isAudio(e.name) {
                    guard tracks.count<20_000 else{throw MusicError("В папке больше 20 000 треков.")}
                    let m=AudioNames.metadata(e.name);tracks.append(Track(id:stableID("\(source.id):\(e.id)"),sourceID:source.id,provider:.google,remoteID:e.id,filename:e.name,title:m.title,artist:m.artist,album:node.depth==0 ? source.name : node.album,resourceKey:e.key))
                }
            }
        }
        source.updatedAt=Date();return FolderContents(source:source,tracks:tracks)
    }
    public func resolve(_ track:Track,source:MusicSource?,fresh:Bool=false) async throws -> URL {
        if track.provider == .local || track.provider == .demo { guard let u=URL(string:track.remoteID),u.isFileURL else{throw MusicError("Локальный файл недоступен.")};return u }
        if !fresh,let link=links[track.id],link.expires>Date(){return link.url}
        let resolved:URL
        if track.provider == .yandex {
            guard let source else{throw MusicError("Подключи исходную папку ещё раз.")}
            let d=try await json(url(yandexBase+"/public/resources/download",["public_key":source.url,"path":track.remoteID]))
            guard let href=d["href"] as? String,let u=URL(string:href),u.scheme=="https" else{throw MusicError("Яндекс Диск не выдал ссылку на аудио.")};resolved=u
        } else {
            var p=["id":track.remoteID,"export":"download"];if !track.resourceKey.isEmpty{p["resourcekey"]=track.resourceKey}
            var next=url("https://drive.usercontent.google.com/download",p),found:URL?
            for _ in 0..<3 {
                let r=try await HTTP.read(next,limit:4096,range:4096)
                let type=(r.response.mimeType ?? "").lowercased()
                if type.contains("html") { let page=try await HTTP.read(next,limit:2_000_000);next=try GooglePage.confirmationURL(String(decoding:page.data,as:UTF8.self)) }
                else if !type.hasPrefix("text/") && !type.contains("json") {found=r.response.url ?? next;break}
                else{throw MusicError("Google Drive не выдал аудиофайл.")}
            }
            guard let result=found else{throw MusicError("Google Drive требует дополнительного подтверждения.")};resolved=result
        }
        links[track.id]=Link(url:resolved,expires:Date().addingTimeInterval(240));trimLinks();return resolved
    }
    private func trimLinks(){if links.count>512 { for key in links.sorted(by:{$0.value.expires<$1.value.expires}).prefix(links.count-512).map(\.key){links.removeValue(forKey:key)} }}
}
