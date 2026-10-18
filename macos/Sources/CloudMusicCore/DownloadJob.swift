import Foundation

public final class DownloadJob:NSObject,URLSessionDownloadDelegate,@unchecked Sendable {
    private let lock=NSLock();private var continuation:CheckedContinuation<URL,Error>?;private var task:URLSessionDownloadTask?;private var session:URLSession?;private var cancelled=false
    private var lastProgress=Date.distantPast
    private let staging:URL;private let progress:@Sendable (Int64,Int64)->Void
    public init(staging:URL,progress:@escaping @Sendable (Int64,Int64)->Void){self.staging=staging;self.progress=progress}
    public func run(_ url:URL) async throws -> URL {
        try await withTaskCancellationHandler(operation:{
            try await withCheckedThrowingContinuation{ c in
                lock.lock();continuation=c
                if cancelled{lock.unlock();finish(.failure(CancellationError()));return}
                let config=URLSessionConfiguration.ephemeral;config.urlCache=nil;config.timeoutIntervalForRequest=45;config.timeoutIntervalForResource=3600
                let s=URLSession(configuration:config,delegate:self,delegateQueue:nil);session=s;let t=s.downloadTask(with:url);task=t;lock.unlock();t.resume()
            }
        },onCancel:{self.cancel()})
    }
    public func cancel(){lock.lock();cancelled=true;let t=task;lock.unlock();t?.cancel()}
    private func finish(_ r:Result<URL,Error>){lock.lock();let c=continuation;continuation=nil;let s=session;session=nil;lock.unlock();c?.resume(with:r);s?.finishTasksAndInvalidate()}
    public func urlSession(_ session:URLSession,downloadTask:URLSessionDownloadTask,didWriteData bytesWritten:Int64,totalBytesWritten:Int64,totalBytesExpectedToWrite:Int64){let now=Date();if now.timeIntervalSince(lastProgress)>=0.15 || totalBytesWritten==totalBytesExpectedToWrite{lastProgress=now;progress(totalBytesWritten,totalBytesExpectedToWrite)}}
    public func urlSession(_ session:URLSession,downloadTask:URLSessionDownloadTask,didFinishDownloadingTo location:URL){
        do {
            guard let response=downloadTask.response as? HTTPURLResponse,response.url?.scheme=="https" || response.url?.host=="127.0.0.1" else{throw MusicError("Небезопасный ответ сервера.")}
            try HTTP.check(response)
            let type=response.mimeType ?? "";guard !type.contains("html"),!type.hasPrefix("text/"),!type.contains("json") else{throw MusicError("Облако вернуло страницу вместо музыки. Проверь доступ к скачиванию.")}
            try FileManager.default.moveItem(at:location,to:staging);finish(.success(staging))
        }catch{finish(.failure(error))}
    }
    public func urlSession(_ session:URLSession,task:URLSessionTask,didCompleteWithError error:Error?){if let error{try? FileManager.default.removeItem(at:staging);finish(.failure(error))}}
}
