import Foundation

/// A stable permutation for the lifetime of one queue/shuffle selection.
public struct PlaybackOrder:Sendable {
    public let indices:[Int]
    public init(count:Int,current:Int=0,shuffle:Bool=false){
        let all=Array(0..<max(0,count))
        indices=shuffle && all.contains(current) ? [current]+all.filter{$0 != current}.shuffled() : all
    }
    public func next(_ index:Int,wrap:Bool=false)->Int?{
        guard let at=indices.firstIndex(of:index) else{return nil}
        if at+1<indices.count{return indices[at+1]};return wrap ? indices.first:nil
    }
    public func previous(_ index:Int)->Int?{
        guard let at=indices.firstIndex(of:index) else{return nil}
        return at>0 ? indices[at-1]:indices.last
    }
}
