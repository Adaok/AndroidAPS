package info.nightscout.androidaps.plugins.general.overview.activities

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import info.nightscout.androidaps.MainApp
import info.nightscout.androidaps.R
import info.nightscout.androidaps.activities.NoSplashAppCompatActivity
import info.nightscout.androidaps.data.QuickWizard
import info.nightscout.androidaps.data.QuickWizard.getActive
import info.nightscout.androidaps.data.QuickWizardEntry
import info.nightscout.androidaps.db.DatabaseHelper
import info.nightscout.androidaps.interfaces.Constraint
import info.nightscout.androidaps.plugins.bus.RxBus
import info.nightscout.androidaps.plugins.configBuilder.ConfigBuilderPlugin
import info.nightscout.androidaps.plugins.configBuilder.ProfileFunctions
import info.nightscout.androidaps.plugins.general.overview.dialogs.EditQuickWizardDialog
import info.nightscout.androidaps.plugins.general.overview.events.EventQuickWizardChange
import info.nightscout.androidaps.utils.DateUtil
import info.nightscout.androidaps.utils.DecimalFormatter
import info.nightscout.androidaps.utils.FabricPrivacy
import info.nightscout.androidaps.utils.OKDialog.show
import info.nightscout.androidaps.utils.plusAssign
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import kotlinx.android.synthetic.main.overview_quickwizardlist_activity.*

class QuickWizardListActivity : NoSplashAppCompatActivity() {

    private val TAG: String = "QuickWizardListActivity"

    private var disposable: CompositeDisposable = CompositeDisposable()

    private inner class RecyclerViewAdapter internal constructor(internal var fragmentManager: FragmentManager) : RecyclerView.Adapter<RecyclerViewAdapter.QuickWizardEntryViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QuickWizardEntryViewHolder {
            return QuickWizardEntryViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.overview_quickwizardlist_item, parent, false), fragmentManager)
        }

        override fun onBindViewHolder(holder: QuickWizardEntryViewHolder, position: Int) {
            holder.from.text = DateUtil.timeString(QuickWizard[position].validFromDate())
            holder.to.text = DateUtil.timeString(QuickWizard[position].validToDate())
            holder.buttonText.text = QuickWizard[position].buttonText()
            holder.carbs.text = DecimalFormatter.to0Decimal(QuickWizard[position].carbs().toDouble()) + " g"
        }

        override fun getItemCount(): Int = QuickWizard.size()

        private inner class QuickWizardEntryViewHolder internal constructor(itemView: View, internal var fragmentManager: FragmentManager) : RecyclerView.ViewHolder(itemView) {
            val buttonText: TextView = itemView.findViewById(R.id.overview_quickwizard_item_buttonText)
            val carbs: TextView = itemView.findViewById(R.id.overview_quickwizard_item_carbs)
            val from: TextView = itemView.findViewById(R.id.overview_quickwizard_item_from)
            val to: TextView = itemView.findViewById(R.id.overview_quickwizard_item_to)

            //TODO change name button variable
            private val takeItButton: Button = itemView.findViewById(R.id.overview_quickwizard_item_take_button)
            private val editButton: Button = itemView.findViewById(R.id.overview_quickwizard_item_edit_button)
            private val removeButton: ImageView = itemView.findViewById(R.id.overview_quickwizard_item_remove_btn)

            init {
                takeItButton.setOnClickListener {
                    //onClickQuickwizard(baseContext, null)
                    //TODO prepare dialog
                    val myWizard = QuickWizard[adapterPosition]
                    onClickQuickwizard(baseContext, myWizard)
                }
                editButton.setOnClickListener {
                    val manager = fragmentManager
                    val editQuickWizardDialog = EditQuickWizardDialog()
                    editQuickWizardDialog.entry = QuickWizard[adapterPosition]
                    editQuickWizardDialog.show(manager, "EditQuickWizardDialog")
                }
                removeButton.setOnClickListener {
                    QuickWizard.remove(adapterPosition)
                    RxBus.send(EventQuickWizardChange())
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.overview_quickwizardlist_activity)

        overview_quickwizardactivity_recyclerview?.setHasFixedSize(true)
        overview_quickwizardactivity_recyclerview?.layoutManager = LinearLayoutManager(this)
        overview_quickwizardactivity_recyclerview?.adapter = RecyclerViewAdapter(supportFragmentManager)

        overview_quickwizardactivity_add_button.setOnClickListener {
            val manager = supportFragmentManager
            val editQuickWizardDialog = EditQuickWizardDialog()
            editQuickWizardDialog.show(manager, "EditQuickWizardDialog")
        }
    }

    override fun onResume() {
        super.onResume()
        disposable += RxBus
            .toObservable(EventQuickWizardChange::class.java)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({
                val adapter = RecyclerViewAdapter(supportFragmentManager)
                overview_quickwizardactivity_recyclerview?.swapAdapter(adapter, false)
            }, {
                FabricPrivacy.logException(it)
            })
    }

    override fun onPause() {
        disposable.clear()
        super.onPause()
    }

    fun onClickQuickwizard(context: Context, quickWizardEntry: QuickWizardEntry) {
        val actualBg = DatabaseHelper.actualBg() //is null also,
        val profile = ProfileFunctions.getInstance().profile
        val profileName = ProfileFunctions.getInstance().profileName
        val pump = ConfigBuilderPlugin.getPlugin().activePump
        //val quickWizardEntry = getActive() //change this by position quickWizard card

        if (actualBg != null && profile != null && pump != null) {
            //quickWizardButton.setVisibility(View.VISIBLE)
            val wizard = quickWizardEntry.doCalc(profile, profileName, actualBg, true)

            if (wizard.calculatedTotalInsulin > 0.0 && quickWizardEntry.carbs() > 0.0) {
                val carbsAfterConstraints = MainApp.getConstraintChecker().applyCarbsConstraints(Constraint(quickWizardEntry.carbs())).value()

                if (Math.abs(wizard.insulinAfterConstraints - wizard.calculatedTotalInsulin) >= pump.pumpDescription.pumpType.determineCorrectBolusStepSize(wizard.insulinAfterConstraints)
                    || carbsAfterConstraints != quickWizardEntry.carbs()) {
                        show(context, MainApp.gs(R.string.treatmentdeliveryerror), """
                        ${MainApp.gs(R.string.constraints_violation)}
                        ${MainApp.gs(R.string.changeyourinput)}
                        """.trimIndent())
                    return
                } else {
                    Log.d(TAG, "not in math")
                    //todo create real log here.
                    return
                }
                wizard.confirmAndExecute(context)
            } else {
                Log.d(TAG, "not in wizard")
            }
        } else {
            Log.d(TAG, "not in quickWizard")
        }
    }

    //region interface
}