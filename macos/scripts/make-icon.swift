import AppKit
let output=CommandLine.arguments[1]
let image=NSImage(size:NSSize(width:1024,height:1024))
image.lockFocus()
NSColor.clear.setFill();NSRect(x:0,y:0,width:1024,height:1024).fill()
let rect=NSRect(x:62,y:62,width:900,height:900)
let path=NSBezierPath(roundedRect:rect,xRadius:195,yRadius:195)
let shadow=NSShadow();shadow.shadowColor=NSColor.black.withAlphaComponent(0.25);shadow.shadowBlurRadius=25;shadow.shadowOffset=NSSize(width:0,height:-10)
NSGraphicsContext.saveGraphicsState();shadow.set();NSColor(calibratedRed:0.06,green:0.08,blue:0.07,alpha:1).setFill();path.fill();NSGraphicsContext.restoreGraphicsState()
NSGradient(starting:NSColor(calibratedRed:0.12,green:0.17,blue:0.14,alpha:1),ending:NSColor(calibratedRed:0.045,green:0.055,blue:0.05,alpha:1))?.draw(in:path,angle:-90)
let green=NSColor(calibratedRed:30/255,green:215/255,blue:96/255,alpha:1)
for (x,height) in [(320.0,260.0),(464.0,440.0),(608.0,340.0)] {
 green.setFill();NSBezierPath(roundedRect:NSRect(x:x,y:(1024-height)/2,width:96,height:height),xRadius:48,yRadius:48).fill()
}
image.unlockFocus()
guard let tiff=image.tiffRepresentation,let rep=NSBitmapImageRep(data:tiff),let png=rep.representation(using:.png,properties:[:]) else{fatalError("Could not render app icon")}
try png.write(to:URL(fileURLWithPath:output))
