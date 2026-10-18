import Foundation

public struct HTTPResult: Sendable { public var data: Data; public var response: HTTPURLResponse }
/// Reads bounded data even if an origin ignores Range. No URLCache or audio file is created.
public final class BoundedRequest: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private let lock=NSLock(); private var buffer=Data(); private var response: HTTPURLResponse?
    private var continuation: CheckedContinuation<HTTPResult,Error>?; private var session: URLSession?; private var task: URLSessionDataTask?
    private let limit: Int; private let truncate: Bool; private var cancelled=false
    public init(limit: Int, truncate: Bool=false) { self.limit=limit;self.truncate=truncate }
    public func run(_ request: URLRequest) async throws -> HTTPResult {
        try await withTaskCancellationHandler(operation: {
            try await withCheckedThrowingContinuation { c in
                lock.lock(); continuation=c
                if cancelled { lock.unlock(); finish(.failure(CancellationError()));return }
                let config=URLSessionConfiguration.ephemeral;config.urlCache=nil;config.requestCachePolicy = .reloadIgnoringLocalCacheData;config.timeoutIntervalForRequest=25;config.timeoutIntervalForResource=60
                let s=URLSession(configuration:config,delegate:self,delegateQueue:nil);session=s;let t=s.dataTask(with:request);task=t;lock.unlock();t.resume()
            }
        },onCancel:{ self.cancel() })
    }
    public func cancel() { lock.lock();cancelled=true;let t=task;lock.unlock();t?.cancel() }
    private func finish(_ result: Result<HTTPResult,Error>) {
        lock.lock();let c=continuation;continuation=nil;let s=session;session=nil;lock.unlock()
        c?.resume(with:result);s?.invalidateAndCancel()
    }
    public func urlSession(_ session: URLSession,dataTask:URLSessionDataTask,didReceive response:URLResponse,completionHandler:@escaping (URLSession.ResponseDisposition)->Void) {
        guard let r=response as? HTTPURLResponse,r.url?.scheme=="https" || r.url?.host=="127.0.0.1" else { completionHandler(.cancel);finish(.failure(MusicError("Небезопасный ответ сервера.")));return }
        self.response=r
        if !truncate,r.expectedContentLength>Int64(limit) { completionHandler(.cancel);finish(.failure(MusicError("Ответ сервера слишком большой.")));return }
        completionHandler(.allow)
    }
    public func urlSession(_ session:URLSession,dataTask:URLSessionDataTask,didReceive data:Data) {
        let count=min(data.count,max(0,limit-buffer.count));buffer.append(data.prefix(count))
        if buffer.count>=limit {
            if truncate,let response { finish(.success(HTTPResult(data:buffer,response:response))) }
            else if data.count>count { finish(.failure(MusicError("Ответ сервера слишком большой."))) }
        }
    }
    public func urlSession(_ session:URLSession,task:URLSessionTask,didCompleteWithError error:Error?) {
        if let error { finish(.failure(error)) } else if let response { finish(.success(HTTPResult(data:buffer,response:response))) } else { finish(.failure(MusicError("Пустой ответ сервера."))) }
    }
}
public enum HTTP {
    public static func read(_ url:URL,limit:Int=8_000_000,range:Int?=nil) async throws -> HTTPResult {
        guard url.scheme=="https" || url.host=="127.0.0.1" else { throw MusicError("Поддерживаются только безопасные ссылки HTTPS.") }
        var request=URLRequest(url:url);request.setValue("CloudMusic/1.0 macOS",forHTTPHeaderField:"User-Agent")
        if let range { request.setValue("bytes=0-\(range-1)",forHTTPHeaderField:"Range") }
        let result=try await BoundedRequest(limit:limit,truncate:range != nil).run(request)
        try check(result.response);return result
    }
    public static func check(_ response:HTTPURLResponse) throws {
        switch response.statusCode {
        case 200...299: break
        case 401,403: throw MusicError("Нет доступа. Разреши просмотр и скачивание по ссылке.")
        case 404: throw MusicError("Папка или файл не найдены.")
        case 429: throw MusicError("Облако временно ограничило запросы. Попробуй позже.")
        default: throw MusicError("Сервис временно недоступен (\(response.statusCode)).")
        }
        if response.url?.host=="accounts.google.com" { throw MusicError("Эта папка требует входа в Google. Открой публичный доступ.") }
    }
}
