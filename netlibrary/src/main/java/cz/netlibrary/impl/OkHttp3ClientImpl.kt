package cz.netlibrary.impl

import android.text.TextUtils
import cz.netlibrary.OPERATION_FAILED
import cz.netlibrary.REQUEST_FAILED
import cz.netlibrary.callback.RequestCallback
import cz.netlibrary.log.HttpLog
import cz.netlibrary.model.RequestConfig
import cz.netlibrary.model.RequestMethod
import cz.netlibrary.requestConfig
import okhttp3.*
import okio.ByteString
import java.io.File
import java.io.IOException
import java.net.URLConnection
import java.net.URLEncoder

/**
 * Created by cz on 2017/6/7.
 * okhttp3请求操作实例对象
 */
class OkHttp3ClientImpl : BaseRequestClient<Response,OkHttpClient>() {

    private val callItems= mutableMapOf<String,MutableList<Call>>()

    override fun getHttpClient(): OkHttpClient = httpClient
    
    override fun syncCall(tag: String, item: RequestConfig, callback: RequestCallback<Response>?): Response? {
        var call:Call?
        var response:Response?=null
        val st = System.currentTimeMillis()
        val errorMessage = requestConfig.errorMessage
        try {
            val request = getRequest(tag, item)
            HttpLog.log { append("发起请求:${request.url()}\n") }
            call = httpClient.newCall(request)
            response = call.execute()
            HttpLog.log { append("真实请求:${request.url()}\n") }
            handleResponse(tag, response, request.url().toString(), callback)
        } catch (e: Exception) {
            //request failed
            HttpLog.log { append("请求操作异常:${e.message} 耗时:${System.currentTimeMillis() - st} 移除Tag:$tag\n") }
            callFailed(callback,OPERATION_FAILED,errorMessage?:e.message,null)
        } finally {
            callItems.remove(tag)
        }
        return response
    }

    override fun call(tag: String, item: RequestConfig,callback:RequestCallback<Response>?) {
        var call:Call?
        val st = System.currentTimeMillis()
        val errorMessage = requestConfig.errorMessage
        try {
            val request = getRequest(tag, item)
            HttpLog.log { append("发起请求:${request.url()}\n") }
            call = httpClient.newCall(request)
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    removeTag(tag)
                    HttpLog.log { append("请求失败:${call.request().url()}\n耗时:${System.currentTimeMillis()-st} 移除Tag:$tag\n") }
                    callFailed(callback,OPERATION_FAILED,errorMessage?:e.message,null)
                }

                @Throws(IOException::class)
                override fun onResponse(call: Call, response: Response) {
                    removeTag(tag)
                    handleResponse(tag, response, request.url().toString(), callback)
                }
            })
        } catch (e: Exception) {
            //request failed
            call=null
            HttpLog.log { append("请求操作异常:${e.message}\n") }
            callFailed(callback,OPERATION_FAILED,errorMessage?:e.message,null)
        }
        try{
            if(null!=tag&&null!=call){
                val items=callItems.getOrPut(tag){ mutableListOf() }
                items.add(call)
                HttpLog.log { append("请求添加Tag:$tag\n") }
            }
        } catch (e:Exception){
        }
    }

    private fun callFailed(callback:RequestCallback<Response>?,code:Int,message:String?,result:String?=null)=callback?.onFailed(code,message,result)

    private fun handleResponse(tag: String, response: Response, url:String, callback: RequestCallback<Response>?) {
        var result: String = getResponseResult(response)
        val time=response.receivedResponseAtMillis()-response.sentRequestAtMillis()
        val code = response.code()
        HttpLog.log { append("请求成功:$url\n请求返回值:$code\n耗时:$time 移除:Tag:$tag\n") }
        if (200 == code) {
            callback?.onSuccess(response, code, result, time)
        } else {
            HttpLog.log { append("请求异常:$code\n结果$result\n") }
            callFailed(callback,REQUEST_FAILED,null,result)
        }
    }

    /**
     * 获得返回结果值
     */
    private fun getResponseResult(response: Response): String {
        var result: String
        if (null != response.cacheResponse()) {
            result = response.cacheResponse().toString()
        } else {
            result = response.body().string()
        }
        return result
    }

    private fun removeTag(tag:String){
        try{
            callItems.remove(tag)
        } catch (e:Exception){
            e.printStackTrace()
        }
    }

    override fun cancel(tag: String) {
        try{
            val items=callItems.remove(tag)
            items?.let { it.forEach { if(!it.isCanceled)it.cancel() } }
        } catch (e:Exception){
            HttpLog.log { append("取消任务:$tag 发生异常:\n${e.message}\n") }
        }
    }

    private fun getRequest(tag: String, item: RequestConfig): Request {
        val requestUrl = item.getRequestUrl()
        var url:StringBuilder
        if(!item.pathValue.isEmpty()){
            url=StringBuilder(String.format(requestUrl, *item.pathValue.toTypedArray()))
        } else {
            url= StringBuilder(requestUrl)
        }
        HttpLog.log { append("请求url:$url \n") }
        //add extras param
        requestConfig.requestExtrasCallback?.invoke(item)?.let {
            val params:(MutableMap<String, Any?>) = mutableMapOf()
            params.putAll(it)
            if(null!=item.params && item.params.isNotEmpty()){
                params.putAll(item.params)
            }
            item.params=params
        }
        var request=when(item.method){
            RequestMethod.post, RequestMethod.put-> getMultipartRequest(tag,url,item)
            RequestMethod.get,RequestMethod.delete->getGetByDeleteRequest(tag,url,item)
        }
        //add cookie
        val cookie = item.cookies.map { it.key.to(it.value) }.joinToString("&") { "${it.first}=${it.second}" }
        HttpLog.log { append("cookie:$cookie\n") }
        val newBuilder = request.newBuilder()
        newBuilder.header("Cookie", cookie)
        request = newBuilder.build()
        return request
    }

    private fun getRequestBody(entity: Pair<String,Any?>?):RequestBody?{
        var requestBody: RequestBody?=null
        if(entity?.second != null) {
            requestBody = when(entity.second){
                is ByteString ->{
                    RequestBody.create(MediaType.parse(entity.first), entity.second as ByteString)
                }
                is ByteArray ->{
                    RequestBody.create(MediaType.parse(entity.first), entity.second as ByteArray)
                }
                is File ->{
                    RequestBody.create(MediaType.parse(entity.first), entity.second as File)
                }
                else -> {
                    RequestBody.create(MediaType.parse(entity.first), entity.second as String)
                }
            }
        }
        return requestBody
    }

    private fun getMultipartRequest(tag: String?, url:StringBuilder, item: RequestConfig):Request{
        var requestBody: RequestBody?=null
        if( null != item.entityJson ){
            requestBody = RequestBody.create(JSON, item.entityJson)
        }else if (null != item.entity) {
            val entity= item.entity?.invoke(item.params)
            requestBody = getRequestBody(entity)
        } else if(null != item.entityPair ){
            val entity= item.entityPair
            requestBody = getRequestBody(entity)
        } else if(!item.params.isEmpty()){
            val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
            item.params.forEach { (key,value)->
                value?.let {
                    if(it !is File){
                        builder.addFormDataPart(key,value.toString())
                    } else {
                        /**
                         * 上传文件格式
                         */
                        //根据文件的后缀名，获得文件类型
                        val fileType = getMimeType(it.name)
                        builder.addFormDataPart( //给Builder添加上传的文件
                                key,  //请求的名字
                                it.name, //文件的文字，服务器端用来解析的
                                RequestBody.create(MediaType.parse(fileType), it)) //创建RequestBody，把上传的文件放入
                    }
                }
            }
            requestBody = builder.build()
        }
        val requestBuilder = Request.Builder().url(url.toString())
        if(null!=requestBody){
            when(item.method){
                RequestMethod.post->requestBuilder.post(requestBody)
                RequestMethod.put->requestBuilder.put(requestBody)
            }
        }
        initRequestBuilder(tag, item, requestBuilder)
        return requestBuilder.build()
    }

    /**
     * 获取文件MimeType
     *
     * @param filename 文件名
     * @return
     */
    private fun getMimeType(filename: String): String {
        val filenameMap = URLConnection.getFileNameMap()
        var contentType = filenameMap.getContentTypeFor(filename)
        if (contentType == null) {
            contentType = "application/octet-stream" //* exe,所有的可执行程序
        }
        return contentType
    }

    /**
     * 获取一个get/delete请求对象
     */
    private fun getGetByDeleteRequest(tag: String?,url:StringBuilder,item: RequestConfig):Request{
        url.append(item.params.map { it.key to if(item.encode) URLEncoder.encode(it.value.toString(),"UTF-8")  else it.value }.joinToString("&") { "${it.first}=${it.second}"})
        val requestBuilder = Request.Builder().url(url.toString())
        if(null!=requestBuilder){
            when(item.method){
                RequestMethod.get->requestBuilder.get()
                RequestMethod.delete->requestBuilder.delete()
            }
        }
        initRequestBuilder(tag, item, requestBuilder)
        return requestBuilder.build()
    }


    /**
     * 初始化requestBuilder对象,主要用于添加header 以及全局header
     */
    private fun initRequestBuilder(tag: String?, item: RequestConfig, requestBuilder: Request.Builder) {
        val headerBuilder = StringBuilder()
        val headers= mutableMapOf<String,String>()
        //添加全局headers
        val extHeaders=requestConfig.requestHeaderCallback?.invoke()
        if(null!=extHeaders&&!extHeaders.isEmpty()){
            headers.putAll(extHeaders)
        }
        //添加配置header,如果重复,并使其覆盖掉全局
        headers.putAll(item.header)
        //遍历并添加所有Header
        headers.filterValues { !TextUtils.isEmpty(it) }.forEach {
            headerBuilder.append(it.key + "=" + it.value + ";")
            requestBuilder.addHeader(it.key, it.value)
        }
        if(null!=tag){
            requestBuilder.tag(tag)
        }
    }


}