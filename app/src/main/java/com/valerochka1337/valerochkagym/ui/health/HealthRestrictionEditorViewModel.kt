package com.valerochka1337.valerochkagym.ui.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valerochka1337.valerochkagym.data.ai.AiApiConfigurationProvider
import com.valerochka1337.valerochkagym.data.ai.HealthAiEndpointDecision
import com.valerochka1337.valerochkagym.data.ai.HealthRestrictionAiInterpreter
import com.valerochka1337.valerochkagym.data.ai.RestrictionInterpretation
import com.valerochka1337.valerochkagym.data.ai.healthAiEndpointDecision
import com.valerochka1337.valerochkagym.data.health.HealthRepository
import com.valerochka1337.valerochkagym.domain.health.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RestrictionInput(val text:String="",val source:HealthInformationSource=HealthInformationSource.USER,val state:HealthRestrictionState=HealthRestrictionState.ACTIVE,val included:Boolean=true)
data class RestrictionAiDisclosure(val host:String,val model:String,val loopbackWarning:Boolean,val originalAtRequest:String)
data class RestrictionEditorState(val originalText:String="",val proposals:List<RestrictionInput> = listOf(RestrictionInput()),val reading:Boolean=false,val disclosure:RestrictionAiDisclosure?=null,val error:String?=null)
@HiltViewModel class HealthRestrictionEditorViewModel @Inject constructor(private val interpreter:HealthRestrictionAiInterpreter,private val repository:HealthRepository,private val configuration:AiApiConfigurationProvider):ViewModel(){
 private val state=MutableStateFlow(RestrictionEditorState()); val uiState:StateFlow<RestrictionEditorState> = state.asStateFlow(); private val done=Channel<Unit>(Channel.BUFFERED); val finished=done.receiveAsFlow()
 fun original(value:String)=state.update{it.copy(originalText=value,disclosure=null)}; fun update(index:Int,row:RestrictionInput)=state.update{it.copy(proposals=it.proposals.toMutableList().also{r->r[index]=row})}; fun add()=state.update{it.copy(proposals=it.proposals+RestrictionInput())}
 fun requestInterpretation()=viewModelScope.launch { val original=state.value.originalText; if(original.isBlank()){state.update{it.copy(error="Введите исходную формулировку")};return@launch}; val c=configuration.requestConfiguration()?:run{state.update{it.copy(error="Настройте нейросеть в настройках")};return@launch}; when(val decision=healthAiEndpointDecision(c.connection.baseUrl,false)){HealthAiEndpointDecision.PublicHttpRejected->state.update{it.copy(error="Медицинский текст нельзя отправить через публичный HTTP")};HealthAiEndpointDecision.Invalid->state.update{it.copy(error="Некорректный адрес нейросети")};HealthAiEndpointDecision.Allowed,HealthAiEndpointDecision.LoopbackConsentRequired->state.update{it.copy(disclosure=RestrictionAiDisclosure(android.net.Uri.parse(c.connection.baseUrl).host.orEmpty(),c.modelId,decision==HealthAiEndpointDecision.LoopbackConsentRequired,original),error=null)}} }
 fun cancelDisclosure()=state.update{it.copy(disclosure=null)}
 fun confirmDisclosure(){val d=state.value.disclosure?:return;if(d.originalAtRequest!=state.value.originalText){state.update{it.copy(disclosure=null,error="Текст изменён: подтвердите отправку заново")};return};state.update{it.copy(disclosure=null,reading=true,error=null)};viewModelScope.launch{when(val r=interpreter.interpret(d.originalAtRequest,d.loopbackWarning)){is RestrictionInterpretation.Success->state.update{it.copy(reading=false,proposals=r.proposals.map{p->RestrictionInput(p.limitedActivity,p.source,p.state)})};is RestrictionInterpretation.Failure->state.update{it.copy(reading=false,error=r.message)}}}}
 fun save()=viewModelScope.launch { val rows=state.value.proposals.filter{it.included&&it.text.isNotBlank()};if(rows.isEmpty()){state.update{it.copy(error="Подтвердите хотя бы одно ограничение")};return@launch};val original=state.value.originalText.takeIf(String::isNotBlank);rows.forEach{row->repository.saveRestriction(ConfirmedHealthRestriction(updatedAt=System.currentTimeMillis(),draft=HealthRestrictionDraft(row.text,row.state,row.source,System.currentTimeMillis())),original)};done.send(Unit) }
}
