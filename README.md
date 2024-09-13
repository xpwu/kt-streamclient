# kt-streamclient 
 stream client 2.0 for android，与 go-stream 配合使用。像使用短链接一样使用
长链接，支持自定义底层协议，支持自定义 Log 的输出。

## 0、代码库的引用
1、使用 jitpack 直接依赖 github 代码   
2、在 root build.grable 加入
```
allprojects {
	repositories {
		google()
		mavenCentral()
		// 在依赖库列表的最后加入jit依赖库
		maven { url 'https://jitpack.io' }
	}
}
```
3、在 module build.grable 加入
```
dependencies {
  // 加入如下依赖
  implementation 'com.github.xpwu:kt-streamclient:2.0.0'
}

```


## 1、基本使用
1、创建client，一个 client 对应一条长链接，在发送数据时自动连接
```
 val client = Client(Option.Host(host), Option.Port(port));
```
或者
```
val client = Client(){// protocol}
```
2、client.Send(xxx) 即可像短连接一样发送请求，同一个client上的所有
请求都是在一条连接中发送。

## 2、push / peerClosed
set client.onPush 即可设定推送的接收函数   
set client.onPeerClosed 即可设定网络被关闭时的接收函数，但主动
调用 client.close() 方法不会触发 onPeerClosed 事件

## 3、recover connection
如果不需要发送数据而仅需恢复网络，可以使用 client.Recover

## 4、Update protocol/options
client.updateOptions / client.UpdateProtocol 更新配置，下一次自动重连时，会使用新的配置

## 5、error
超时会返回 TimeoutStError，其他情况是 StError.
