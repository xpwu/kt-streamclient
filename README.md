# kt-streamclient 
 stream client 2.0 for android，与 [go-stream](https://github.com/xpwu/go-stream) 配合使用。像使用短链接一样使用
长链接，支持自定义底层协议(此库已默认支持自定义的 LenContent 及 标准的 websocket 协议)，支持自定义 Log 的输出。

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
  // 加入如下依赖，x.x.x填写为具体使用的版本号
  implementation 'com.github.xpwu:kt-streamclient:x.x.x'
}

```


## 1、基本使用
1、创建client，一个 client 对应一条长链接，在发送数据时自动连接
```kotlin
// lencontent 协议
val client = Client.withLenContent(Host("xxx.xxx.xxx.xxx"), Port(xx))
// 或者 websocket 协议
val client = Client.withWebsocket(Url("ws://xxx.xxx.xxx.xxx:xx"))
```
或者
```kotlin
val client = Client(){ /* protocol */ }
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
client.UpdateProtocol 更新配置，下一次自动重连时，会使用新的配置

## 5、error
超时会返回 TimeoutStError，其他情况是 StError.

## 6、test case
拉取 test 代码，并需要在文件 local.properties 添加如下内容:
```properties
test.host=xxx.xxx.xxx.xxx
test.port=xx
test.url=ws://xxx.xxx.xxx.xxx:xx
```
测试用例需要自己搭建 go-stream 服务[streamserver](https://github.com/xpwu/streamserver)，
并把上面的字段填写为对应服务器的设置。
