package cz.netlibrary.request

import cz.netlibrary.model.RequestMethod

/**
 * Created by cz on 2017/6/7.
 */
class PostRequest{
    var method = RequestMethod.post
    var info:String?=null
    var url:String=String()
    var entity:((MutableMap<String, Any?>)->Pair<String,Any?>)?=null
    var entityPair:Pair<String,Any?>?=null
    var entityJson:String?=null
    var pathValue:Array<String>?=null
    var params:Map<String,Any?>?=null
    var header:Map<String,String>?=null
}