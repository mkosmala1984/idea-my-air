package dev.myair.toolwindow

import com.intellij.ide.util.treeView.TreeState
import com.intellij.openapi.util.JDOMUtil
import org.jdom.Element

/** [state] as the XML text an application property can hold across restarts. */
fun treeStateXml(state: TreeState): String = JDOMUtil.write(Element("tree").also(state::writeExternal))

/** The state [treeStateXml] wrote, or nothing expanded when nothing was written. */
fun treeStateOf(xml: String?): TreeState = TreeState.createFrom(xml?.let(JDOMUtil::load) ?: Element("tree"))
