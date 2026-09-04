package com.valerochka1337.valerochkagym.ui.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.valerochka1337.valerochkagym.data.ai.AiApiConfigurationProvider
import com.valerochka1337.valerochkagym.data.ai.AiApiRequestConfiguration
import com.valerochka1337.valerochkagym.data.ai.HealthAiEndpointDecision
import com.valerochka1337.valerochkagym.data.ai.HealthRestrictionAiInterpreter
import com.valerochka1337.valerochkagym.data.ai.RestrictionInterpretation
import com.valerochka1337.valerochkagym.data.ai.healthAiEndpointDecision
import com.valerochka1337.valerochkagym.data.health.HealthRepository
import com.valerochka1337.valerochkagym.data.db.dao.HealthDao
import com.valerochka1337.valerochkagym.ui.navigation.GymRoutes
import com.valerochka1337.valerochkagym.domain.health.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import java.util.UUID

data class RestrictionInput(val text:String="",val source:HealthInformationSource=HealthInformationSource.USER,val state:HealthRestrictionState=HealthRestrictionState.ACTIVE,val startsAt: Long? = null, val reviewAt: Long? = null, val included:Boolean=true)
data class RestrictionAiDisclosure(val host:String,val model:String,val loopbackWarning:Boolean,val originalAtRequest:String)
data class RestrictionEditorState(val originalText:String="",val proposals:List<RestrictionInput> = listOf(RestrictionInput()),val reading:Boolean=false,val disclosure:RestrictionAiDisclosure?=null,val error:String?=null,val editingId:String?=null,val lifting:Boolean=false)
@HiltViewModel class HealthRestrictionEditorViewModel @Inject constructor(private val interpreter:HealthRestrictionAiInterpreter,private val repository:HealthRepository,private val configuration:AiApiConfigurationProvider,private val healthDao: HealthDao, savedStateHandle: SavedStateHandle):ViewModel(){
 private val state=MutableStateFlow(RestrictionEditorState(editingId=savedStateHandle.get(GymRoutes.HEALTH_RESTRICTION_ID_ARG))); val uiState:StateFlow<RestrictionEditorState> = state.asStateFlow(); private val done=Channel<Unit>(Channel.BUFFERED); val finished=done.receiveAsFlow(); private var disclosedConfiguration: AiApiRequestConfiguration? = null; private var saveOperationId: String? = null; private var existingVersion:Long?=null; private var existingOriginal:String?=null
 init { state.value.editingId?.let { id -> viewModelScope.launch { healthDao.restriction(id)?.let { restriction -> existingVersion=restriction.version; existingOriginal=restriction.originalText; state.update { it.copy(originalText=restriction.originalText.orEmpty(),proposals=listOf(RestrictionInput(restriction.description,HealthInformationSource.valueOf(restriction.source),HealthRestrictionState.valueOf(restriction.status),restriction.startsAt,restriction.reviewAt))) } } ?: state.update { it.copy(error="Ограничение не найдено") } } } }
 fun original(value:String)=state.update{it.copy(originalText=value,disclosure=null)}; fun update(index:Int,row:RestrictionInput)=state.update{it.copy(proposals=it.proposals.toMutableList().also{r->r[index]=row})}; fun add()=state.update{it.copy(proposals=it.proposals+RestrictionInput())}
 fun requestInterpretation()=viewModelScope.launch { val original=state.value.originalText; if(original.isBlank()){state.update{it.copy(error="Введите исходную формулировку")};return@launch}; val c=configuration.requestConfiguration()?:run{state.update{it.copy(error="Настройте нейросеть в настройках")};return@launch}; when(val decision=healthAiEndpointDecision(c.connection.baseUrl,false)){HealthAiEndpointDecision.PublicHttpRejected->state.update{it.copy(error="Медицинский текст нельзя отправить через публичный HTTP")};HealthAiEndpointDecision.Invalid->state.update{it.copy(error="Некорректный адрес нейросети")};HealthAiEndpointDecision.Allowed,HealthAiEndpointDecision.LoopbackConsentRequired->{disclosedConfiguration=c;state.update{it.copy(disclosure=RestrictionAiDisclosure(android.net.Uri.parse(c.connection.baseUrl).host.orEmpty(),c.modelId,decision==HealthAiEndpointDecision.LoopbackConsentRequired,original),error=null)}}} }
 fun cancelDisclosure(){disclosedConfiguration=null;state.update{it.copy(disclosure=null)}}
 fun confirmDisclosure(){val d=state.value.disclosure?:return;val c=disclosedConfiguration?:return;if(d.originalAtRequest!=state.value.originalText){disclosedConfiguration=null;state.update{it.copy(disclosure=null,error="Текст изменён: подтвердите отправку заново")};return};disclosedConfiguration=null;state.update{it.copy(disclosure=null,reading=true,error=null)};viewModelScope.launch{when(val r=interpreter.interpret(d.originalAtRequest,c,d.loopbackWarning)){is RestrictionInterpretation.Success->state.update{it.copy(reading=false,proposals=r.proposals.map{p->RestrictionInput(p.limitedActivity,p.source,p.state,p.startsAt,p.reviewAt)})};is RestrictionInterpretation.Failure->state.update{it.copy(reading=false,error=r.message)}}}}
 fun save()=viewModelScope.launch { val rows=state.value.proposals.filter{it.included&&it.text.isNotBlank()};if(rows.isEmpty()){state.update{it.copy(error="Подтвердите хотя бы одно ограничение")};return@launch};val original=state.value.originalText.takeIf(String::isNotBlank);val now=System.currentTimeMillis();try{val editing=state.value.editingId;if(editing!=null){val row=rows.singleOrNull()?:throw IllegalStateException("Редактируйте одно ограничение");repository.saveRestriction(ConfirmedHealthRestriction(editing,(existingVersion?:0)+1,now,HealthRestrictionDraft(row.text,row.state,row.source,now,row.startsAt,row.reviewAt)),existingOriginal?:original)}else repository.confirmRestrictionProposals(ConfirmRestrictionProposalsCommand(saveOperationId?:UUID.randomUUID().toString().also{saveOperationId=it},rows.map{row->HealthRestrictionProposal(UUID.randomUUID().toString(),row.text,row.state,row.source,row.startsAt,row.reviewAt)},original),now);done.send(Unit)}catch(_:Exception){state.update{it.copy(error="Не удалось сохранить ограничения")}} }
 fun lift()=viewModelScope.launch { val id=state.value.editingId?:return@launch; state.update{it.copy(lifting=true,error=null)};try{repository.liftRestriction(id,System.currentTimeMillis());done.send(Unit)}catch(_:Exception){state.update{it.copy(lifting=false,error="Не удалось снять ограничение")}} }
}
