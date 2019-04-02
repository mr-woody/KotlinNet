package cz.netlibrary.model

/**
 * Created by cz on 2017/6/7.
 */
class RequestConfig{
    var action:String?=null
    var method = RequestMethod.get
    var url:String=String()
    var encode:Boolean=true
    var info:String?=null
    var pathValue = mutableListOf<String>()
    var entity:((MutableMap<String, Any?>)->Pair<String,Any?>)?=null
    var entityPair:Pair<String,Any?>?=null
    var entityJson:String?=null
    var cookies= mutableMapOf<String,String>()
    var params= mutableMapOf<String,Any?>()
    var header= mutableMapOf<String,String>()
}